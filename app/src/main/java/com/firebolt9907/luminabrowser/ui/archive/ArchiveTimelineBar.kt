package com.firebolt9907.luminabrowser.ui.archive

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firebolt9907.luminabrowser.ui.LuminaHaptics
import com.firebolt9907.luminabrowser.ui.MorphingBannerHost
import com.firebolt9907.luminabrowser.ui.archive.WaybackManager.ArchiveSession
import com.firebolt9907.luminabrowser.ui.archive.WaybackManager.DayCapture
import com.firebolt9907.luminabrowser.ui.archive.WaybackManager.Phase
import com.firebolt9907.luminabrowser.ui.archive.WaybackManager.YearData
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape
import java.time.LocalDate
import kotlinx.coroutines.launch

private val MONTH_NAMES = arrayOf(
    "", "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
private val WEEKDAY_INITIALS = listOf("S", "M", "T", "W", "T", "F", "S")

private fun formatSelected(ts: String?): String? {
    if (ts == null || ts.length < 8) return null
    val year = ts.substring(0, 4)
    val month = ts.substring(4, 6).toIntOrNull() ?: return null
    val day = ts.substring(6, 8).toIntOrNull() ?: return null
    return "${MONTH_NAMES.getOrElse(month) { "" }.take(3)} $day, $year"
}

/**
 * Bottom-anchored Internet Archive control, floating above the address pill (same placement pattern
 * as [com.firebolt9907.luminabrowser.ui.sync.OtpPasteBannerHost]). It's a slim bar — attribution,
 * the currently-viewed date, a **Pick date** button and a Close button. Tapping *Pick date* opens a
 * modal with the Wayback-desktop-style selector (a **year bar graph** over a **month calendar**
 * whose capture days are circled by frequency).
 *
 * Hidden while [visible] is false (e.g. the pill has collapsed to compact) and whenever [session]
 * is null (archive mode off for the selected tab).
 *
 * @param onSelectYear fired when the user taps a year in the bar graph (loads that year's calendar).
 * @param onSelect     fired with a 14-digit capture timestamp when the user picks a day.
 * @param onExit       fired when the user closes the control (returns to the live page).
 */
@Composable
fun ArchiveTimelineBarHost(
    session: ArchiveSession?,
    visible: Boolean,
    bottomPadding: Dp,
    // Grid-open progress (0..1). The bar fades out with the pill as the tab switcher opens, so it
    // stays anchored to the pill instead of hovering statically over the opening grid.
    gridProgress: () -> Float = { 0f },
    // Horizontal pill offset (px) during a tab-switch swipe — the bar tracks it so it slides with
    // the current tab's pill instead of sitting still.
    horizontalOffsetX: () -> Float = { 0f },
    // 0..1 fade applied only as the tab-switch settle completes (not during the drag), so the bar
    // disappears exactly when the switch finishes without a visible fade mid-swipe.
    switchFade: () -> Float = { 0f },
    // Scroll-driven pill collapse progress (0 = pill expanded, 1 = pill compact) — the SAME signal
    // the address pill morphs on. Read per-frame in the draw phase so the bar tracks the scroll
    // "intent" 1:1 with the pill: scrolling down dives it toward the pill, scrolling up pops it back.
    collapseProgress: () -> Float = { 0f },
    // Shared Now-Bar stack so this bar piles up with the sync/OTP banners instead of overlapping.
    stack: com.firebolt9907.luminabrowser.ui.BannerStackState? = null,
    onSelectYear: (Int) -> Unit,
    onSelect: (String) -> Unit,
    onExit: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    // Close the modal if archive mode ends underneath it.
    LaunchedEffect(session == null) { if (session == null) showPicker = false }

    // All the enter/exit morph, scroll-collapse intent, pill tracking and pill-matched padding live
    // in the shared MorphingBannerHost (see its doc) — every floating banner behaves identically.
    MorphingBannerHost(
        visible = visible && session != null,
        bottomPadding = bottomPadding,
        // Replay the spring-out whenever we land on a different tab that's in archive mode, so the
        // bar animates in on a tab switch rather than appearing already-settled.
        enterKey = session?.tabId,
        // Bottom of the stack (nearest the pill) — it's the persistent context bar; transient
        // notifications pile above it.
        stack = stack,
        stackId = "archive",
        stackPriority = 0,
        collapseProgress = collapseProgress,
        gridProgress = gridProgress,
        horizontalOffsetX = horizontalOffsetX,
        switchFade = switchFade,
    ) {
        // Hold the last session so the bar doesn't blank out mid-exit-morph after state clears.
        var last by remember { mutableStateOf(session) }
        if (session != null) last = session
        last?.let {
            SlimArchiveBar(
                session = it,
                onPickDate = { showPicker = true },
                onExit = onExit,
            )
        }
    }

    if (showPicker) {
        session?.let { s ->
            ArchivePickerModal(
                session = s,
                onSelectYear = onSelectYear,
                onSelect = { ts -> onSelect(ts); showPicker = false },
                onDismiss = { showPicker = false },
            )
        }
    }
}

/** The always-visible slim bar: attribution + current date + Pick date + Close. */
@Composable
private fun SlimArchiveBar(
    session: ArchiveSession,
    onPickDate: () -> Unit,
    onExit: () -> Unit,
) {
    val haptics = rememberLuminaHaptics()
    val subtitle = formatSelected(session.selected) ?: when (session.phase) {
        Phase.Loading -> "Finding snapshots…"
        Phase.Empty -> "No snapshots for this page"
        Phase.Error -> "Couldn't reach the Internet Archive"
        Phase.Ready -> "Wayback Machine · archive.org"
    }
    Surface(
        // surfaceContainerHighest = the pill's own fill color, so the banner matches the pill below.
        shape = SquircleShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Internet Archive",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = { haptics.select(); onPickDate() },
                enabled = session.phase == Phase.Ready,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(if (session.selected != null) "Change" else "Pick date")
            }
            IconButton(onClick = { haptics.softClick(); onExit() }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit Internet Archive",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The date-picker modal: the Wayback-style year bar graph over a month calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivePickerModal(
    session: ArchiveSession,
    onSelectYear: (Int) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberLuminaHaptics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Pick a date",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "Snapshots from the Internet Archive · Wayback Machine",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            when (session.phase) {
                Phase.Loading -> StatusRow(spinner = true, text = "Finding snapshots…")
                Phase.Empty -> StatusRow(spinner = false, text = "No snapshots for this page")
                Phase.Error -> StatusRow(spinner = false, text = "Couldn't reach the Internet Archive")
                Phase.Ready -> ArchiveCalendarPicker(
                    session = session,
                    haptics = haptics,
                    onSelectYear = onSelectYear,
                    onSelect = onSelect,
                )
            }

            // Save Page Now — request a fresh capture of the live page so a new snapshot exists.
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (saving) return@OutlinedButton
                    haptics.softClick()
                    saving = true
                    Toast.makeText(context, "Saving to the Internet Archive…", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val ok = WaybackManager.instance.savePage(session.originalUrl)
                        saving = false
                        Toast.makeText(
                            context,
                            if (ok) "Saved a new snapshot to the Internet Archive"
                            else "Couldn't save right now (rate-limited or offline)",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Saving…")
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Save this page to the Archive now")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(spinner: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (spinner) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The signature Wayback element: one vertical bar per year, height ∝ capture count, tappable. */
@Composable
private fun YearBarGraph(
    years: List<YearData>,
    selectedYear: Int?,
    haptics: LuminaHaptics,
    onSelectYear: (Int) -> Unit,
) {
    val maxTotal = remember(years) { (years.maxOfOrNull { it.total } ?: 1).coerceAtLeast(1) }
    val listState = rememberLazyListState()
    LaunchedEffect(years, selectedYear) {
        val idx = years.indexOfFirst { it.year == selectedYear }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        items(years, key = { it.year }) { yd ->
            val selected = yd.year == selectedYear
            val barColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            // Height scaled by count; a floor so a 1-capture year is still a visible tap target.
            val frac = (yd.total.toFloat() / maxTotal).coerceIn(0.06f, 1f)
            val barMaxHeight = 46.dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { haptics.select(); onSelectYear(yd.year) }
                    .padding(horizontal = 3.dp, vertical = 2.dp)
                    .height(66.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(barMaxHeight * frac)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(barColor),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "'" + (yd.year % 100).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The year bar graph over a swipeable month calendar. Owns the currently-viewed year/month so it
 * survives the lazy per-year loads. Swipe horizontally (or use ‹ ›) to change month; crossing
 * January/December rolls into the adjacent year (and loads it). The grid is always six week-rows so
 * the modal height never jumps between months.
 */
@Composable
private fun ArchiveCalendarPicker(
    session: ArchiveSession,
    haptics: LuminaHaptics,
    onSelectYear: (Int) -> Unit,
    onSelect: (String) -> Unit,
) {
    val years = session.years
    val minYear = years.firstOrNull()?.year ?: return
    val maxYear = years.lastOrNull()?.year ?: return

    // Every (year, month) from minYear-Jan to maxYear-Dec is a pager page, so month changes are a
    // smooth finger-following swipe and crossing Jan/Dec rolls into the adjacent year.
    val totalPages = (maxYear - minYear + 1) * 12
    fun pageToYear(p: Int) = minYear + p / 12
    fun pageToMonth(p: Int) = p % 12 + 1

    val initYear = session.selectedYear ?: maxYear
    val initMonth = remember {
        val s = session.selected
        val fromSel = if (s != null && s.length >= 6 && s.startsWith(initYear.toString())) {
            s.substring(4, 6).toIntOrNull()
        } else null
        fromSel ?: session.yearDays.maxOfOrNull { it.month } ?: 1
    }
    val pagerState = rememberPagerState(
        initialPage = (initYear - minYear) * 12 + (initMonth - 1),
        pageCount = { totalPages },
    )
    val scope = rememberCoroutineScope()

    val viewYear = pageToYear(pagerState.currentPage)
    val viewMonth = pageToMonth(pagerState.currentPage)

    // When the pager settles on a different year, load that year's calendar (per-day captures).
    LaunchedEffect(pagerState.settledPage) {
        val y = pageToYear(pagerState.settledPage)
        if (y != session.selectedYear) onSelectYear(y)
    }

    Column(Modifier.fillMaxWidth()) {
        YearBarGraph(
            years = years,
            selectedYear = viewYear,
            haptics = haptics,
            onSelectYear = { y ->
                // Jump the pager to that year, keeping the current month.
                val target = (y - minYear) * 12 + (viewMonth - 1)
                scope.launch { pagerState.animateScrollToPage(target) }
            },
        )
        Spacer(Modifier.height(8.dp))

        // Month header with ‹ › (disabled only at the very first / very last month overall).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                enabled = pagerState.currentPage > 0,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = "${MONTH_NAMES.getOrElse(viewMonth) { "" }} $viewYear",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                enabled = pagerState.currentPage < totalPages - 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        // Weekday header.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            WEEKDAY_INITIALS.forEach { d ->
                Text(
                    text = d,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(2.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) { p ->
            val y = pageToYear(p)
            val mo = pageToMonth(p)
            // Days are available only for the year that's currently loaded; adjacent-year pages show
            // an empty (but full-height) grid until they settle and load.
            val dataReady = y == session.selectedYear && !session.yearLoading
            val monthDays = if (dataReady) session.yearDays.filter { it.month == mo } else emptyList()
            val maxDayCount = (session.yearDays.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
            MonthGrid(
                year = y,
                month = mo,
                days = monthDays,
                maxDayCount = maxDayCount,
                selected = session.selected,
                haptics = haptics,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    year: Int,
    month: Int,
    days: List<DayCapture>,
    maxDayCount: Int,
    selected: String?,
    haptics: LuminaHaptics,
    onSelect: (String) -> Unit,
) {
    val captureByDay = remember(days) { days.associateBy { it.day } }
    // Sunday-first leading blanks: LocalDate.dayOfWeek is MON(1)..SUN(7); map SUN→0.
    val firstDow = remember(year, month) { LocalDate.of(year, month, 1).dayOfWeek.value % 7 }
    val daysInMonth = remember(year, month) { LocalDate.of(year, month, 1).lengthOfMonth() }
    // Always six week-rows so the modal height is constant across months (some months span 4–6
    // weeks); trailing rows are simply empty.
    val rows = 6

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val cellIndex = r * 7 + c
                    val dayNum = cellIndex - firstDow + 1
                    // Fixed cell height so empty (leading/trailing) rows still occupy their slot —
                    // keeps the six-row grid a constant height regardless of month.
                    Box(
                        modifier = Modifier.weight(1f).height(38.dp).padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayNum in 1..daysInMonth) {
                            DayCell(
                                dayNum = dayNum,
                                capture = captureByDay[dayNum],
                                maxDayCount = maxDayCount,
                                isSelected = captureByDay[dayNum]?.timestamp == selected,
                                haptics = haptics,
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    dayNum: Int,
    capture: DayCapture?,
    maxDayCount: Int,
    isSelected: Boolean,
    haptics: LuminaHaptics,
    onSelect: (String) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    if (capture == null) {
        // No capture: plain, dim, non-tappable.
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Text(
                text = dayNum.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        return
    }
    // Capture day: filled circle whose opacity tracks capture frequency (desktop "bigger dot").
    val intensity = (capture.count.toFloat() / maxDayCount).coerceIn(0f, 1f)
    val fill = primary.copy(alpha = 0.35f + 0.55f * intensity)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (isSelected) primary else fill)
            .then(
                if (isSelected) Modifier.border(2.dp, primary, CircleShape) else Modifier,
            )
            .clickable { haptics.step(); onSelect(capture.timestamp) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dayNum.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected || intensity > 0.5f) Color.White
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
