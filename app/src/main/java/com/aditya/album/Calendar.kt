package com.aditya.album

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/** One day that has photos: how many, the newest one (whose thumbnail stands for the day), and where they start in the list. */
class PhotoDay<T>(val count: Int, val first: T, val from: Int)

/** Newest-first [media] counted per day, keyed by epoch day, newest day first. */
fun <T> photoDays(media: List<T>, dayOf: (T) -> Long): Map<Long, PhotoDay<T>> {
    val out = LinkedHashMap<Long, PhotoDay<T>>()
    var start = 0
    for (i in 1..media.size) {
        if (i == media.size || dayOf(media[i]) != dayOf(media[start])) {
            out[dayOf(media[start])] = PhotoDay(i - start, media[start], start)
            start = i
        }
    }
    return out
}

/** [month] as weeks of 7 epoch days starting on [weekStart], with null for the blanks before the 1st and after the last day. */
fun weeksOf(month: YearMonth, weekStart: DayOfWeek): List<List<Long?>> {
    val first = month.atDay(1)
    val blanks = (first.dayOfWeek.value - weekStart.value + 7) % 7
    val cells = List(blanks) { null } + List(month.lengthOfMonth()) { first.toEpochDay() + it }
    return cells.chunked(7) { it + List(7 - it.size) { null } }
}

/** What TalkBack reads for a calendar day, like "24 October, 12 photos" or "24 October, no photos". */
fun dayDescription(day: Long, count: Int): String {
    val date = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMMM"))
    return when (count) {
        0 -> "$date, no photos"
        1 -> "$date, 1 photo"
        else -> "$date, $count photos"
    }
}

/** The day in [sortedDays] (ascending epoch days) closest to [day], the older one on a tie; null when there are none. */
fun nearestDay(sortedDays: LongArray, day: Long): Long? {
    if (sortedDays.isEmpty()) return null
    val i = sortedDays.binarySearch(day)
    if (i >= 0) return day
    val after = -i - 1 // the first later day
    return when {
        after == 0 -> sortedDays[0]
        after == sortedDays.size -> sortedDays.last()
        day - sortedDays[after - 1] <= sortedDays[after] - day -> sortedDays[after - 1]
        else -> sortedDays[after]
    }
}

/** The days in [sortedDays] (ascending) from [reach] days before [day] to [reach] days after, oldest first. */
fun daysAround(sortedDays: LongArray, day: Long, reach: Int = 3): List<Long> {
    val from = sortedDays.binarySearch(day - reach).let { if (it >= 0) it else -it - 1 }
    return (from until sortedDays.size).map { sortedDays[it] }.takeWhile { it <= day + reach }
}

/** The newest of [sortedDays] (ascending) within [from]..[to], or null. */
fun newestIn(sortedDays: LongArray, from: Long, to: Long): Long? {
    val i = sortedDays.binarySearch(to).let { if (it >= 0) it else -it - 2 }
    return sortedDays.getOrNull(i)?.takeIf { it >= from }
}

private val shortDay = DateTimeFormatter.ofPattern("d MMM")
private val shortOldDay = DateTimeFormatter.ofPattern("d MMM yyyy")

/** "24 Oct", with the year when it isn't [year]. */
fun shortDate(day: Long, year: Int): String = LocalDate.ofEpochDay(day).let { it.format(if (it.year == year) shortDay else shortOldDay) }

/** "1 photo", "12,480 photos", the number grouped the phone's way. */
fun photoCount(n: Int): String = if (n == 1) "1 photo" else "${NumberFormat.getIntegerInstance().format(n)} photos"

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")
private val stripDay = DateTimeFormatter.ofPattern("EEE d")
private val stripOldDay = DateTimeFormatter.ofPattern("EEE d yyyy")

/** Room for the chosen day's ring, which sits 2dp outside the day and is 2dp wide. */
private val ringRoom = Space.s1

// The design's strip photos: 56dp, the chosen day's 72dp
private val stripPhoto = Space.s12 + Space.s2
private val stripPhotoChosen = Space.s12 + Space.s6

/**
 * "Jump to a date": a sheet with a month of days, each showing its newest photo or, as a heatmap, how many it has; the
 * photos from three days before to three days after the chosen day; and a button that shows that day in the Library.
 * [media] must be the list the Library grid shows, newest first. [onDay] gets the epoch day to show (the chosen day,
 * or the nearest day with photos when it has none); [onOpen] a photo tapped in the strip. The caller closes the sheet
 * after either, as on [onDismiss].
 */
@Composable
fun CalendarSheet(media: List<Media>, onDismiss: () -> Unit, onDay: (Long) -> Unit, onOpen: (Media) -> Unit) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val still = AlbumTheme.spec.still
    val haptics by rememberUpdatedState(rememberHaptics())
    val scope = rememberCoroutineScope()
    val locale = Locale.getDefault()
    val weekStart = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val initials = remember(weekStart, locale) { List(7) { weekStart.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale) } }
    val today = remember { LocalDate.now().toEpochDay() }
    // Built once per photo list: about 3,000 days for 20,000 photos, well under a millisecond
    val days = remember(media) { photoDays(media) { it.day } }
    val sorted = remember(days) { days.keys.toLongArray().apply { sort() } }
    // The months run from the first photo's to this one (or a later photo's, when a camera clock was wrong)
    val firstMonth = remember(sorted) { YearMonth.from(LocalDate.ofEpochDay(sorted.firstOrNull() ?: today)) }
    val lastMonth = remember(sorted) { maxOf(YearMonth.from(LocalDate.ofEpochDay(today)), YearMonth.from(LocalDate.ofEpochDay(sorted.lastOrNull() ?: today))) }
    val monthCount = (ChronoUnit.MONTHS.between(firstMonth, lastMonth) + 1).toInt()
    fun pageOf(day: Long) = ChronoUnit.MONTHS.between(firstMonth, YearMonth.from(LocalDate.ofEpochDay(day))).toInt().coerceIn(0, monthCount - 1)

    var picked by rememberSaveable { mutableLongStateOf(today) }
    var heat by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = pageOf(picked)) { monthCount }
    // Swiping (or a chevron) to another month picks its 1st, as in the design: the strip then shows the photos around it
    LaunchedEffect(pager, firstMonth) {
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            if (page != pageOf(picked)) {
                picked = firstMonth.plusMonths(page.toLong()).atDay(1).toEpochDay()
                haptics.tick()
            }
        }
    }
    fun goTo(page: Int) {
        scope.launch { if (still) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }

    val shown = firstMonth.plusMonths(pager.currentPage.toLong())
    val monthTotal = remember(shown, days) { (1..shown.lengthOfMonth()).sumOf { days[shown.atDay(it).toEpochDay()]?.count ?: 0 } }
    val years = remember(sorted) { sorted.map { LocalDate.ofEpochDay(it).year }.distinct().sortedDescending() }
    val pickedYear = LocalDate.ofEpochDay(picked).year
    val around = remember(sorted, picked) { daysAround(sorted, picked) }
    val target = if (picked in days) picked else nearestDay(sorted, picked) // the day the button shows
    val nearestOnly = around.isEmpty() // nothing within three days: the strip shows the nearest day instead
    val strip = if (nearestOnly) listOfNotNull(target) else around

    Sheet(onDismiss, title = "Jump to a date") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = Space.s3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    years.forEach { y ->
                        Chip("$y", {
                            CrashLog.note("calendar year")
                            // The year's newest day with photos, like the months
                            newestIn(sorted, LocalDate.of(y, 1, 1).toEpochDay(), LocalDate.of(y, 12, 31).toEpochDay())?.let {
                                picked = it
                                scope.launch { pager.scrollToPage(pageOf(it)) }
                            }
                        }, selected = y == shown.year)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Chip("Photos", { heat = false }, selected = !heat)
                    Chip("Heatmap", { heat = true }, selected = heat)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shown.format(monthTitle), Modifier.semantics { heading() }, style = type.titleMd, color = c.ink)
                    Text(if (monthTotal > 0) photoCount(monthTotal) else "No photos this month", style = type.meta, color = c.inkMuted)
                }
                Row(Modifier.offset(x = Space.s3)) { // the buttons' touch area reaches into the margin, their icons line up with the days
                    AlbumIconButton(Sym.ChevronLeft, "Previous month", { goTo(pager.currentPage - 1) }, enabled = pager.currentPage > 0)
                    AlbumIconButton(Sym.ChevronRight, "Next month", { goTo(pager.currentPage + 1) }, enabled = pager.currentPage < monthCount - 1)
                }
            }
            // Hidden from TalkBack: every day already says its date
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s2).clearAndSetSemantics {}, Arrangement.spacedBy(Space.s1)) {
                initials.forEach { Text(it, Modifier.weight(1f), style = type.caption, color = c.inkMuted, textAlign = TextAlign.Center) }
            }
            MonthPager(pager, firstMonth, weekStart, days, picked, today, heat) { day ->
                CrashLog.note("calendar day")
                haptics.tick()
                picked = day
            }
            if (heat) Legend(Modifier.padding(top = Space.s2))
            if (target != null) {
                Row(Modifier.fillMaxWidth().padding(top = Space.s6, bottom = Space.s2)) {
                    Text(
                        if (nearestOnly) "Nearest photos: ${shortDate(target, pickedYear)}" else "Around ${shortDate(picked, pickedYear)}",
                        Modifier.weight(1f).alignByBaseline(),
                        style = type.titleMd,
                        color = c.ink,
                    )
                    Text(photoCount(strip.sumOf { days[it]?.count ?: 0 }), Modifier.alignByBaseline(), style = type.meta, color = c.inkMuted)
                }
                AroundStrip(strip, days, media, picked, pickedYear) {
                    CrashLog.note("calendar open")
                    onOpen(it)
                }
                AlbumButton(
                    "Show ${shortDate(target, pickedYear)} in Library",
                    {
                        CrashLog.note("calendar jump")
                        onDay(target)
                    },
                    Modifier.fillMaxWidth().padding(top = Space.s4),
                    ButtonVariant.Primary,
                    Sym.CalendarMonth,
                )
            }
        }
    }
}

/**
 * The months side by side: swipe between them. Each has only the weeks it needs, as in the design. The pager reaches
 * [ringRoom] past the days on both sides, so the chosen day's ring isn't cut off at the edges.
 */
@Composable
private fun MonthPager(
    pager: PagerState,
    firstMonth: YearMonth,
    weekStart: DayOfWeek,
    days: Map<Long, PhotoDay<Media>>,
    picked: Long,
    today: Long,
    heat: Boolean,
    onPick: (Long) -> Unit,
) {
    HorizontalPager(
        pager,
        Modifier.fillMaxWidth().layout { measurable, constraints ->
            val room = ringRoom.roundToPx()
            val wider = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * room, maxWidth = constraints.maxWidth + 2 * room))
            layout(constraints.maxWidth, wider.height) { wider.place(-room, 0) }
        },
        contentPadding = PaddingValues(horizontal = ringRoom),
        pageSpacing = Space.s4,
        key = { it },
    ) { page ->
        val month = firstMonth.plusMonths(page.toLong())
        val weeks = remember(month, weekStart) { weeksOf(month, weekStart) }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            weeks.forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
                    week.forEach { day ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            if (day != null) DayCell(day, days[day], heat, selected = day == picked, isToday = day == today) { onPick(day) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A day: its newest photo with the date on a pill, or in [heat] view a flash-soft (1 to 4 photos) or flash (5 or more)
 * fill. The chosen day gets a flash-ink ring 2dp outside it; today, unless it shows a photo, a line-strong ring inside.
 * Days without photos can be chosen too: the strip then shows the nearest ones.
 */
@Composable
private fun DayCell(day: Long, photos: PhotoDay<Media>?, heat: Boolean, selected: Boolean, isToday: Boolean, onPick: () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val shape = RoundedCornerShape(Space.radiusMd)
    val count = photos?.count ?: 0
    val thumb = if (heat) null else photos?.first
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !still) 0.92f else 1f, label = "dayPress")
    val (fill, ink) = when {
        heat && count >= 5 -> c.flash to c.onFlash
        heat && count > 0 -> c.flashSoft to c.ink
        else -> Color.Transparent to if (count > 0) c.ink else c.inkMuted
    }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (!selected) Modifier
                else Modifier.drawBehind {
                    val out = ringRoom.toPx() - 1.dp.toPx() // the 2dp ring's middle, 3dp out
                    drawRoundRect(
                        c.flashInk, Offset(-out, -out), Size(size.width + 2 * out, size.height + 2 * out),
                        CornerRadius(Space.radiusMd.toPx() + out), style = Stroke(2.dp.toPx()),
                    )
                },
            )
            .selectable(selected, interactionSource = source, indication = null, onClick = onPick)
            .semantics { contentDescription = (if (isToday) "Today, " else "") + dayDescription(day, count) }
            .clip(shape)
            .background(fill)
            .then(if (isToday && thumb == null) Modifier.border(1.dp, c.lineStrong, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val number = LocalDate.ofEpochDay(day).dayOfMonth.toString()
        if (thumb != null) {
            Thumbnail(thumb, Modifier.fillMaxSize())
            Text(
                number,
                Modifier.align(Alignment.TopStart).padding(3.dp).background(c.scrimPhoto, CircleShape).padding(horizontal = 5.dp).clearAndSetSemantics {},
                style = AlbumTheme.type.caption.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                color = c.onPhoto,
            )
        } else {
            Text(
                number,
                Modifier.clearAndSetSemantics {},
                style = AlbumTheme.type.label.copy(fontSize = 13.sp, fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Medium),
                color = ink,
            )
        }
    }
}

/** What the heatmap's fills mean. Each day also says its count to TalkBack, so colour is never the only signal. */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val keyShape = RoundedCornerShape(Space.s1)
    FlowRow(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        listOf(Color.Transparent to "No photos", c.flashSoft to "1 to 4", c.flash to "5 or more").forEach { (fill, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                // a line ring on all but the flash key, as in the design
                Box(Modifier.size(Space.s3).background(fill, keyShape).then(if (fill == c.flash) Modifier else Modifier.border(1.dp, c.line, keyShape)))
                Text(label, style = AlbumTheme.type.caption, color = c.inkMuted)
            }
        }
    }
}

/**
 * The photos of [strip]'s days side by side (up to six a day), the chosen day's larger and scrolled to the centre.
 * Tapping one calls [onOpen].
 */
@Composable
private fun AroundStrip(strip: List<Long>, days: Map<Long, PhotoDay<Media>>, media: List<Media>, picked: Long, pickedYear: Int, onOpen: (Media) -> Unit) {
    val c = AlbumTheme.colors
    val spec = AlbumTheme.spec
    val scroll = rememberScrollState()
    var width by remember { mutableIntStateOf(0) }
    var centreDay by remember { mutableLongStateOf(-1L) } // the chosen day whose group was last placed, and its centre
    var centre by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(picked, centreDay, centre, width) {
        if (centreDay != picked || width == 0) return@LaunchedEffect
        val to = (centre - width / 2f).roundToInt().coerceIn(0, scroll.maxValue)
        if (spec.still) scroll.scrollTo(to) else scroll.animateScrollTo(to)
    }
    Row(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        strip.forEach { day ->
            val photos = days[day] ?: return@forEach
            key(day) {
                val chosen = day == picked
                Column(
                    if (chosen) Modifier.onPlaced { centreDay = day; centre = it.positionInParent().x + it.size.width / 2f } else Modifier,
                    verticalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    Text(
                        LocalDate.ofEpochDay(day).let { it.format(if (it.year == pickedYear) stripDay else stripOldDay) },
                        style = if (chosen) AlbumTheme.type.caption.copy(fontWeight = FontWeight.Bold) else AlbumTheme.type.caption,
                        color = if (chosen) c.ink else c.inkMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spec.gutter), verticalAlignment = Alignment.CenterVertically) {
                        val size = if (chosen) stripPhotoChosen else stripPhoto
                        media.subList(photos.from, photos.from + minOf(photos.count, 6)).forEach { m ->
                            Thumbnail(
                                m,
                                Modifier.size(size).clip(RoundedCornerShape(spec.tileCorner))
                                    .clickable { onOpen(m) }
                                    .semantics { contentDescription = tileLabel(m, selected = false) },
                            )
                        }
                        if (photos.count > 6) Text("+${photos.count - 6}", Modifier.padding(horizontal = Space.s2), style = AlbumTheme.type.meta, color = c.inkMuted)
                    }
                }
            }
        }
    }
}
