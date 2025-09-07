package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size

@Composable
fun InsightsScreen(
    authVm: AuthViewModel = viewModel(),
    logVm: LogViewModel,
    onOpenLog: () -> Unit = {},
    onOpenLogForDate: ((LocalDate) -> Unit)? = null
) {
    val auth by authVm.state.collectAsState()
    val logState by logVm.state.collectAsState()

    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }

    // Ensure history is loaded (for options + chart)
    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (token != null && logState.history.isEmpty()) {
            runCatching { logVm.loadHistory(token) }
        }
    }

    // Weather (ALL rows, no filtering)
    var weatherRows by remember { mutableStateOf<List<WeatherRow>>(emptyList()) }
    // Weather toggles
    var showTemp     by remember { mutableStateOf(true) }
    var showPressure by remember { mutableStateOf(true) }
    var showHumidity by remember { mutableStateOf(true) }

    LaunchedEffect(auth.accessToken, showTemp, showPressure, showHumidity) {
        val token = auth.accessToken
        if (token != null && (showTemp || showPressure || showHumidity)) {
            runCatching { listWeatherDailyAll(token) }
                .onSuccess { weatherRows = it }
                .onFailure { weatherRows = emptyList() }
        } else weatherRows = emptyList()
    }

    // Build unique sets for options from history
    val migrainesAll: List<String> = remember(logState.history) {
        logState.history.filter { it.type == "Migraine" }
            .map { it.title.substringBefore(" (sev").trim() }
            .distinct()
            .sorted()
    }
    val medicinesAll: List<String> = remember(logState.history) {
        logState.history.filter { it.type == "Medicine" }
            .map { it.title.trim() }
            .distinct()
            .sorted()
    }
    val reliefsAll: List<String> = remember(logState.history) {
        logState.history.filter { it.type == "Relief" }
            .map { it.title.trim() }
            .distinct()
            .sorted()
    }

    // Selected filters (default = all selected)
    var selectedMigraineTypes by remember(migrainesAll) { mutableStateOf(migrainesAll.toMutableSet()) }
    var selectedMedicines     by remember(medicinesAll) { mutableStateOf(medicinesAll.toMutableSet()) }
    var selectedReliefTypes   by remember(reliefsAll)   { mutableStateOf(reliefsAll.toMutableSet()) }

    // Full time bounds (all data we have)
    val firstEventDate: LocalDate? = remember(logState.history) {
        logState.history.mapNotNull { runCatching { it.at.atZone(zone).toLocalDate() }.getOrNull() }.minOrNull()
    }
    val lastEventDate: LocalDate? = remember(logState.history) {
        logState.history.mapNotNull { runCatching { it.at.atZone(zone).toLocalDate() }.getOrNull() }.maxOrNull()
    }
    val firstWeatherDate: LocalDate? = remember(weatherRows) {
        weatherRows.minOfOrNull { LocalDate.parse(it.date) }
    }
    val lastWeatherDate: LocalDate? = remember(weatherRows) {
        weatherRows.maxOfOrNull { LocalDate.parse(it.date) }
    }

    val endDynamic = listOfNotNull(
        today.plusDays(1),
        lastEventDate?.plusDays(1),
        lastWeatherDate?.plusDays(1)
    ).maxOrNull() ?: today.plusDays(1)

    val startAll = listOfNotNull(firstEventDate, firstWeatherDate).minOrNull()
        ?: endDynamic.minusMonths(2) // fallback if no data

    // Selected day for the Details card
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Chart (no filter on data; viewport zoom)
        item {
            CombinedTimelineChart(
                startAll = startAll,
                endDateExclusive = endDynamic,
                history = logState.history,
                weatherRows = weatherRows,
                selectedMigraineTypes = selectedMigraineTypes,
                selectedMedicines = selectedMedicines,
                selectedReliefTypes = selectedReliefTypes,
                showTemp = showTemp,
                showPressure = showPressure,
                showHumidity = showHumidity,
                onSelectDate = { d -> selectedDate = d }
            )
        }

        // Details scorecard (pops under chart when a day is clicked)
        if (selectedDate != null) {
            val d = selectedDate!!
            item {
                DayDetailsCard(
                    date = d,
                    history = logState.history,
                    weatherRows = weatherRows,
                    onOpenLog = {
                        // Optional: wire date-aware navigation externally if needed
                        // onOpenLogForDate?.invoke(d) ?: onOpenLog()
                        onOpenLogForDate?.invoke(d) ?: onOpenLog()
                    },
                    onClose = { selectedDate = null }
                )
            }
        }

        // ===================== Options =====================
        item {
            Text(
                "Options",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
            )
        }

        // 1) Migraines card
        item {
            SectionCard(title = "Migraines") {
                if (migrainesAll.isEmpty()) {
                    Text("No migraines recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        migrainesAll.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val checked = name in selectedMigraineTypes
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        val m = selectedMigraineTypes.toMutableSet()
                                        if (it) m.add(name) else m.remove(name)
                                        selectedMigraineTypes = m
                                    }
                                )
                                Text(name)
                            }
                        }
                    }
                }
            }
        }

        // 2) Reliefs card
        item {
            SectionCard(title = "Reliefs") {
                if (reliefsAll.isEmpty()) {
                    Text("No reliefs recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        reliefsAll.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val checked = name in selectedReliefTypes
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        val m = selectedReliefTypes.toMutableSet()
                                        if (it) m.add(name) else m.remove(name)
                                        selectedReliefTypes = m
                                    }
                                )
                                Text(name)
                            }
                        }
                    }
                }
            }
        }

        // 3) Medicines card
        item {
            SectionCard(title = "Medicines") {
                if (medicinesAll.isEmpty()) {
                    Text("No medicines recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        medicinesAll.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val checked = name in selectedMedicines
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        val m = selectedMedicines.toMutableSet()
                                        if (it) m.add(name) else m.remove(name)
                                        selectedMedicines = m
                                    }
                                )
                                Text(name)
                            }
                        }
                    }
                }
            }
        }

        // 4) Weather card (group remaining toggles)
        item {
            SectionCard(title = "Weather") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showTemp, onCheckedChange = { showTemp = it })
                        Text("Temperature")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showPressure, onCheckedChange = { showPressure = it })
                        Text("Pressure")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showHumidity, onCheckedChange = { showHumidity = it })
                        Text("Humidity")
                    }
                }
            }
        }
    }
}

/* ------------------ Details card ------------------ */

@Composable
private fun DayDetailsCard(
    date: LocalDate,
    history: List<HistoryItem>,
    weatherRows: List<WeatherRow>,
    onOpenLog: () -> Unit,
    onClose: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val itemsForDay = remember(history, date) {
        history.filter { it.at.atZone(zone).toLocalDate() == date }
            .sortedBy { it.at }
    }
    val weather = remember(weatherRows, date) {
        weatherRows.firstOrNull { LocalDate.parse(it.date) == date }
    }

    SectionCard(title = "Details — ${date.format(DATE_FMT)}") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (itemsForDay.isEmpty() && weather == null) {
                Text("No data for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // Weather block (ROUNDED values)
                if (weather != null) {
                    Column {
                        Text("Weather", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        val parts = buildList {
                            weather.temp_c?.let { add("Temp: ${it.roundToInt()} °C") }
                            weather.pressure_hpa?.let { add("Pressure: ${it.roundToInt()} hPa") }
                            weather.humidity_pct?.let { add("Humidity: ${it.roundToInt()}%") }
                        }
                        if (parts.isEmpty()) {
                            Text("No weather values.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(parts.joinToString("   •   "))
                        }
                    }
                }

                // Events block (use SAME ICONS as chart)
                if (itemsForDay.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text("Events", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    itemsForDay.forEach { h ->
                        val time = h.at.atZone(zone).toLocalTime().toString().take(5) // HH:mm
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                when (h.type) {
                                    "Migraine" -> Icon(
                                        Icons.Outlined.Psychology, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    "Medicine" -> Icon(
                                        Icons.Outlined.Medication, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    "Relief" -> Icon(
                                        Icons.Outlined.Spa, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(h.title, maxLines = 2)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onOpenLog) { Text("Open Log") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/* ------------------ Chart (all data; viewport zoom) ------------------ */

private data class Metric(
    val key: String,
    val values: List<Double?>,
    val unit: String,
    val color: Color
)

@Composable
private fun CombinedTimelineChart(
    startAll: LocalDate,
    endDateExclusive: LocalDate, // exclusive
    history: List<HistoryItem>,
    weatherRows: List<WeatherRow>,
    selectedMigraineTypes: Set<String>,
    selectedMedicines: Set<String>,
    selectedReliefTypes: Set<String>,
    showTemp: Boolean,
    showPressure: Boolean,
    showHumidity: Boolean,
    onSelectDate: (LocalDate) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val daysAsc = remember(startAll, endDateExclusive) {
        generateSequence(startAll) { it.plusDays(1) }
            .takeWhile { it.isBefore(endDateExclusive) }
            .toList()
    }
    val dayCount = daysAsc.size
    val dayIndex = remember(daysAsc) { daysAsc.withIndex().associate { it.value to it.index } }
    fun Instant.toLocalDate(): LocalDate = this.atZone(zone).toLocalDate()

    // Aggregate events — respect per-item selections
    val mig = IntArray(dayCount)
    val med = IntArray(dayCount)
    val rel = IntArray(dayCount)
    history.forEach { h ->
        val d = h.at.toLocalDate()
        if (!d.isBefore(startAll) && d.isBefore(endDateExclusive)) {
            val idx = dayIndex[d] ?: return@forEach
            when (h.type) {
                "Migraine" -> {
                    val label = h.title.substringBefore(" (sev").trim()
                    if (label in selectedMigraineTypes) mig[idx]++
                }
                "Medicine" -> {
                    val name = h.title.trim()
                    if (name in selectedMedicines) med[idx]++
                }
                "Relief" -> {
                    val r = h.title.trim()
                    if (r in selectedReliefTypes) rel[idx]++
                }
            }
        }
    }

    // Weather series map (ALL)
    val wMap = remember(weatherRows) { weatherRows.associateBy { LocalDate.parse(it.date) } }
    val temp = daysAsc.map { d -> wMap[d]?.temp_c }
    val pres = daysAsc.map { d -> wMap[d]?.pressure_hpa }
    val hum  = daysAsc.map { d -> wMap[d]?.humidity_pct }

    // Selected metrics
    val metrics = buildList {
        if (showTemp)     add(Metric("Temp",     temp, "°C",  MaterialTheme.colorScheme.primary))
        if (showPressure) add(Metric("Pressure", pres, "hPa", MaterialTheme.colorScheme.secondary))
        if (showHumidity) add(Metric("Humidity", hum,  "%",   MaterialTheme.colorScheme.tertiary))
    }
    val singleMetric = metrics.size == 1
    val dualAxis = metrics.size == 2

    // Zoom (viewport): days visible across width
    val minVisibleDays = 14
    val maxVisibleDays = max(dayCount, minVisibleDays)
    var visibleDays by remember(dayCount) { mutableIntStateOf(min(60, maxVisibleDays)) } // ~2 months

    // Geometry & scroll
    val chartHeight: Dp = 300.dp
    val leftAxisW: Dp = when {
        dualAxis || singleMetric -> 56.dp
        else -> 28.dp
    }
    val rightAxisW: Dp = if (dualAxis) 56.dp else 0.dp
    val scroll = rememberScrollState()
    var plotWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // day width from available plot width and desired visible days
    val dayWidthDp: Dp = remember(plotWidthPx, visibleDays) {
        with(density) {
            val px = if (visibleDays > 0) (plotWidthPx.toFloat() / visibleDays) else 0f
            val clamped = px.coerceIn(6f, 90f)
            clamped.toDp()
        }
    }
    val dayWidthPx: Float = with(density) { dayWidthDp.toPx() }
    val totalWidthPx: Float = dayWidthPx * dayCount
    val totalWidthDp: Dp = with(density) { totalWidthPx.toDp() }

    // Auto-scroll to last ~2 months
    var hasAutoScrolled by remember(dayCount, plotWidthPx, visibleDays) { mutableStateOf(false) }
    LaunchedEffect(dayCount, plotWidthPx, visibleDays) {
        if (!hasAutoScrolled && plotWidthPx > 0 && dayCount > 0) {
            val target = (totalWidthPx - plotWidthPx).coerceAtLeast(0f)
            scroll.scrollTo(target.toInt())
            hasAutoScrolled = true
        }
    }

    // Viewport window
    val leftIndex: Int = ((scroll.value.toFloat() / dayWidthPx).toInt()).coerceIn(0, (dayCount - 1).coerceAtLeast(0))
    val rightExclusive: Int = (leftIndex + visibleDays).coerceAtMost(dayCount)
    val visStartDate: LocalDate? = daysAsc.getOrNull(leftIndex)
    val visEndDate: LocalDate? = daysAsc.getOrNull((rightExclusive - 1).coerceAtLeast(0))
    val visDaysCount = (rightExclusive - leftIndex).coerceAtLeast(0)

    SectionCard(title = "") {
        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val midline   = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
        val weekLine  = gridColor.copy(alpha = 0.25f)

        // ---- Chart area ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            // LEFT axis
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(leftAxisW)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                when {
                    dualAxis -> {
                        val (_, _, labels) = remember(metrics) { axisInfo(metrics[0].values, metrics[0].unit) }
                        Text(labels.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(labels.last(),  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    singleMetric -> {
                        val (_, _, labels) = remember(metrics) { axisInfo(metrics[0].values, metrics[0].unit) }
                        Text(labels.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(labels.last(),  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // RIGHT axis (dual only)
            if (dualAxis) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(rightAxisW)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    val (_, _, labels) = remember(metrics) { axisInfo(metrics[1].values, metrics[1].unit) }
                    Text(labels.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(labels.last(),  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Scrollable plot area (Canvas)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = leftAxisW, end = rightAxisW)
                    .onSizeChanged { plotWidthPx = it.width }
                    .horizontalScroll(scroll)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(totalWidthDp)
                        .fillMaxHeight()
                ) {
                    val w = size.width
                    val h = size.height
                    val padTop = 16f
                    val padBottom = 28f
                    val plotTop = padTop
                    val plotBottom = h - padBottom
                    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
                    val pxPerDay = w / max(1f, dayCount.toFloat())

                    // Grid
                    for (i in 0..4) {
                        val y = plotTop + plotHeight * (i / 4f)
                        drawLine(
                            color = if (i == 2) midline else gridColor,
                            start = androidx.compose.ui.geometry.Offset(0f, y),
                            end = androidx.compose.ui.geometry.Offset(w, y),
                            strokeWidth = if (i == 2) 2f else 1f
                        )
                    }
                    // Month & week guides
                    daysAsc.forEachIndexed { idx, date ->
                        if (date.dayOfMonth == 1) {
                            val x = pxPerDay * idx
                            drawLine(
                                color = gridColor,
                                start = androidx.compose.ui.geometry.Offset(x, plotTop),
                                end = androidx.compose.ui.geometry.Offset(x, plotBottom),
                                strokeWidth = 1f
                            )
                        }
                        if (idx % 7 == 0) {
                            val x = pxPerDay * idx
                            drawLine(
                                color = weekLine,
                                start = androidx.compose.ui.geometry.Offset(x, plotTop),
                                end = androidx.compose.ui.geometry.Offset(x, plotBottom),
                                strokeWidth = 1f
                            )
                        }
                    }

                    // Data
                    fun drawNormalized(vals: List<Double?>, color: Color) {
                        val xs = vals.filterNotNull()
                        if (xs.isEmpty()) return
                        val minY = xs.minOrNull()!!
                        val maxY = xs.maxOrNull()!!
                        val range = (maxY - minY).takeIf { it != 0.0 } ?: 1.0
                        val path = Path()
                        var started = false
                        vals.forEachIndexed { i, v ->
                            val cx = pxPerDay * (i + 0.5f)
                            if (v == null) started = false else {
                                val n = ((v - minY) / range).toFloat().coerceIn(0f, 1f)
                                val cy = plotBottom - n * (plotBottom - plotTop)
                                if (!started) { path.moveTo(cx, cy); started = true } else path.lineTo(cx, cy)
                                drawCircle(color = color, radius = 3.5f, center = androidx.compose.ui.geometry.Offset(cx, cy))
                            }
                        }
                        drawPath(path = path, color = color, style = Stroke(width = 3f))
                    }

                    val metricsLocal = metrics // capture
                    if (dualAxis) {
                        val leftVals = metricsLocal[0].values
                        val rightVals = metricsLocal[1].values
                        val leftScale = scaleFor(leftVals)
                        val rightScale = scaleFor(rightVals)
                        drawSeriesWithScale(leftVals, leftScale, pxPerDay, plotTop, plotBottom, metricsLocal[0].color)
                        drawSeriesWithScale(rightVals, rightScale, pxPerDay, plotTop, plotBottom, metricsLocal[1].color)
                    } else if (singleMetric) {
                        val vals = metricsLocal[0].values
                        val sc = scaleFor(vals)
                        drawSeriesWithScale(vals, sc, pxPerDay, plotTop, plotBottom, metricsLocal[0].color)
                    } else {
                        metricsLocal.forEach { drawNormalized(it.values, it.color) }
                    }
                }

                // ---- Events overlay ON the graph (fixed-size icons; tap shows details for that date) ----
                val iconSize = 18.dp
                Row(
                    modifier = Modifier
                        .width(totalWidthDp)
                        .fillMaxHeight()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    val dayW = dayWidthDp
                    daysAsc.forEachIndexed { idx, date ->
                        val hasAny = (mig[idx] > 0) || (med[idx] > 0) || (rel[idx] > 0)
                        val dayBox = Modifier
                            .offset(x = (idx * dayW.value).dp)
                            .width(dayW)
                            .fillMaxHeight()
                            .let { base ->
                                if (hasAny) base.clickable { onSelectDate(date) } else base
                            }

                        Column(
                            modifier = dayBox,
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (mig[idx] > 0) {
                                    Icon(Icons.Outlined.Psychology, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(iconSize))
                                }
                                if (med[idx] > 0) {
                                    Icon(Icons.Outlined.Medication, null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(iconSize))
                                }
                                if (rel[idx] > 0) {
                                    Icon(Icons.Outlined.Spa, null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(iconSize))
                                }
                            }
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }

                // ---- Zoom buttons ON the graph (bottom-right) ----
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                ) {
                    val chipBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                    val chip = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(chipBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp)

                    Text("−",
                        modifier = chip.clickable {
                            visibleDays = (visibleDays + 7).coerceAtMost(maxVisibleDays) // zoom OUT
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("+",
                        modifier = chip.clickable {
                            visibleDays = (visibleDays - 7).coerceAtLeast(minVisibleDays) // zoom IN
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // ---- X-axis: day numbers + month strip (scrolls with chart) ----
        Column(modifier = Modifier.fillMaxWidth()) {
            // Day numbers
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .padding(start = leftAxisW, end = rightAxisW)
                    .horizontalScroll(scroll)
            ) {
                Row(modifier = Modifier.width(totalWidthDp).fillMaxHeight()) {
                    val dayW = dayWidthDp
                    daysAsc.forEachIndexed { idx, date ->
                        val xOffset = (idx * dayW.value).dp
                        Box(
                            modifier = Modifier
                                .offset(x = xOffset)
                                .width(dayW)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            val step = when {
                                dayWidthDp < 10.dp -> 14
                                dayWidthDp < 16.dp -> 7
                                else -> 1
                            }
                            if (idx % step == 0) {
                                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            // Month strip
            Row(
                modifier = Modifier
                    .height(24.dp)
                    .padding(start = leftAxisW, end = rightAxisW, top = 2.dp)
                    .horizontalScroll(scroll)
            ) {
                Row(modifier = Modifier.width(totalWidthDp).fillMaxHeight()) {
                    val segments = remember(daysAsc) { monthSegments(daysAsc) }
                    val dayW = dayWidthDp
                    segments.forEach { seg ->
                        val segWidth = (seg.endExclusive - seg.startIndex).coerceAtLeast(1)
                        val segWidthDp = (segWidth * dayW.value).dp
                        Box(
                            modifier = Modifier.width(segWidthDp).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = seg.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Viewport status (no event count)
        Text(
            modifier = Modifier.padding(top = 4.dp, start = leftAxisW),
            text = buildString {
                append("Showing: ")
                append(visStartDate?.format(DATE_FMT) ?: "—")
                append(" → ")
                append(visEndDate?.format(DATE_FMT) ?: "—")
                append(" (~")
                append(visDaysCount)
                append(" days)")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ---------- Month strip helper ---------- */

private data class MonthSeg(
    val startIndex: Int,
    val endExclusive: Int,
    val ym: YearMonth,
    val label: String
)

private fun monthSegments(daysAsc: List<LocalDate>): List<MonthSeg> {
    if (daysAsc.isEmpty()) return emptyList()
    val out = mutableListOf<MonthSeg>()
    var curYm = YearMonth.from(daysAsc.first())
    var start = 0
    for (i in 1 until daysAsc.size) {
        val ym = YearMonth.from(daysAsc[i])
        if (ym != curYm) {
            out += MonthSeg(
                startIndex = start,
                endExclusive = i,
                ym = curYm,
                label = curYm.format(MONTH_FMT)
            )
            curYm = ym
            start = i
        }
    }
    out += MonthSeg(
        startIndex = start,
        endExclusive = daysAsc.size,
        ym = curYm,
        label = curYm.format(MONTH_FMT)
    )
    return out
}

/* ---------- Axis helpers ---------- */

private fun axisInfo(values: List<Double?>, unit: String): Triple<Double, Double, List<String>> {
    val xs = values.filterNotNull()
    val mn = xs.minOrNull() ?: 0.0
    val mx = xs.maxOrNull() ?: 1.0
    val fmt = tickFormatter(mn, mx, unit)
    return Triple(mn, mx, listOf(fmt(mx), fmt(mn)))
}

private fun tickFormatter(min: Double, max: Double, unit: String): (Double) -> String {
    val range = max - min
    val decimals = when {
        unit == "%" -> 0
        range >= 50 -> 0
        range >= 10 -> 1
        else -> 1
    }
    val pattern = if (decimals == 0) "#0" else "#0.${"0".repeat(decimals)}"
    val df = DecimalFormat(pattern)
    return { v: Double -> df.format(v) + " " + unit }
}

private data class Scale(val min: Double, val max: Double) {
    fun y(v: Double, top: Float, bottom: Float): Float {
        val r = (max - min).takeIf { it != 0.0 } ?: 1.0
        val n = ((v - min) / r).toFloat().coerceIn(0f, 1f)
        return bottom - n * (bottom - top)
    }
}

private fun scaleFor(values: List<Double?>): Scale {
    val xs = values.filterNotNull()
    val mn = xs.minOrNull() ?: 0.0
    val mx = xs.maxOrNull() ?: 1.0
    return Scale(mn, mx)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesWithScale(
    vals: List<Double?>,
    scale: Scale,
    pxPerDay: Float,
    plotTop: Float,
    plotBottom: Float,
    color: Color
) {
    val path = Path()
    var started = false
    vals.forEachIndexed { i, v ->
        val cx = pxPerDay * (i + 0.5f)
        if (v == null) {
            started = false
        } else {
            val cy = scale.y(v, plotTop, plotBottom)
            if (!started) { path.moveTo(cx, cy); started = true } else path.lineTo(cx, cy)
            drawCircle(color = color, radius = 3.5f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        }
    }
    drawPath(path = path, color = color, style = Stroke(width = 3f))
}

/* --------------------------------- REST: weather_daily (ALL rows) --------------------------------- */

private data class WeatherRow(
    val date: String,           // "yyyy-MM-dd"
    val temp_c: Double?,
    val pressure_hpa: Double?,
    val humidity_pct: Double?
)

private suspend fun listWeatherDailyAll(
    accessToken: String
): List<WeatherRow> = withContext(Dispatchers.IO) {
    val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey = BuildConfig.SUPABASE_ANON_KEY
    val url = "$baseUrl/rest/v1/weather_daily" +
            "?select=date,temp_c,pressure_hpa,humidity_pct" +
            "&order=date.asc"

    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("apikey", anonKey)
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) error("weather_daily GET failed ($code): $body")
        val arr = JSONArray(body)
        val out = ArrayList<WeatherRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                WeatherRow(
                    date = o.optString("date"),
                    temp_c = if (o.isNull("temp_c")) null else o.getDouble("temp_c"),
                    pressure_hpa = if (o.isNull("pressure_hpa")) null else o.getDouble("pressure_hpa"),
                    humidity_pct = if (o.isNull("humidity_pct")) null else o.getDouble("humidity_pct")
                )
            )
        }
        out
    } finally {
        conn.disconnect()
    }
}

/* --------------------------------- Utils --------------------------------- */
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
private fun Instant.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    this.atZone(zone).toLocalDate()
