package com.aditya.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** One day that has photos: how many, and the newest one, whose thumbnail stands for the day. */
class PhotoDay<T>(val count: Int, val first: T)

/** Newest-first [media] counted per day, keyed by epoch day, newest day first. */
fun <T> photoDays(media: List<T>, dayOf: (T) -> Long): Map<Long, PhotoDay<T>> {
    val out = LinkedHashMap<Long, PhotoDay<T>>()
    var start = 0
    for (i in 1..media.size) {
        if (i == media.size || dayOf(media[i]) != dayOf(media[start])) {
            out[dayOf(media[start])] = PhotoDay(i - start, media[start])
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

/** What TalkBack reads for a calendar day, like "24 October, 12 photos". */
fun dayDescription(day: Long, count: Int): String {
    val date = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMMM"))
    return when (count) {
        0 -> date
        1 -> "$date, 1 photo"
        else -> "$date, $count photos"
    }
}

/**
 * Full-screen calendar over Photos, newest month first, with a year row for jumping back. Days with photos show the
 * newest one; tapping such a day calls [onDay] with its epoch day. [media] must be the list the Photos grid shows.
 */
@Composable
fun CalendarScreen(media: List<Media>, onBack: () -> Unit, onDay: (Long) -> Unit) {
    val locale = Locale.getDefault()
    val weekStart = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val initials = remember(weekStart, locale) { List(7) { weekStart.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale) } }
    // Built once per photo list change: about 3,000 days and 100 months for 20,000 photos, well under a millisecond
    val days = remember(media) { photoDays(media) { it.day } }
    val months = remember(days, weekStart) {
        days.keys.map { YearMonth.from(LocalDate.ofEpochDay(it)) }.distinct().map { it to weeksOf(it, weekStart) }
    }
    // The year of every lazy item (each month's title, then its weeks): highlights the year row and finds where a year starts
    val yearAt = remember(months) { months.flatMap { (month, weeks) -> List(1 + weeks.size) { month.year } } }
    val years = remember(yearAt) { yearAt.distinct() }
    val today = remember { LocalDate.now().toEpochDay() }
    val list = rememberLazyListState()
    val year by remember(yearAt) { derivedStateOf { yearAt.getOrNull(list.firstVisibleItemIndex) } }
    val gap = LocalLook.current.gridGap

    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {} // catches taps on empty space, which would otherwise reach the photos underneath
            .systemBarsPadding(),
    ) {
        TitleBar("Calendar", onBack = onBack)
        if (years.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp)) {
                years.forEach { y ->
                    val requester = remember { BringIntoViewRequester() }
                    // Keeps the highlighted year on screen while the months scroll past
                    LaunchedEffect(y == year) { if (y == year) requester.bringIntoView() }
                    FilterChip(
                        selected = y == year,
                        onClick = {
                            CrashLog.note("calendar year")
                            list.requestScrollToItem(yearAt.indexOf(y))
                        },
                        label = { Text("$y") },
                        modifier = Modifier.bringIntoViewRequester(requester),
                    )
                }
            }
        }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            months.forEach { (month, weeks) ->
                item(key = "$month", contentType = "month") { MonthTitle(month, initials, gap) }
                weeks.forEachIndexed { i, week ->
                    item(key = "$month/$i", contentType = "week") {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            week.forEach { day ->
                                Box(Modifier.weight(1f)) {
                                    if (day != null) {
                                        Day(day, days[day], isToday = day == today) {
                                            CrashLog.note("calendar day")
                                            onDay(day)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "October 2026" and the weekday initials, lined up with the day columns below. */
@Composable
private fun MonthTitle(month: YearMonth, initials: List<String>, gap: Dp) {
    Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
        Text(
            month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            Modifier.padding(bottom = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        // Hidden from TalkBack: every day already says its date
        Row(Modifier.clearAndSetSemantics {}, Arrangement.spacedBy(gap)) {
            initials.forEach {
                Text(
                    it,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** A day with photos shows the newest one, its date and how many; an empty day is just a muted date. */
@Composable
private fun Day(day: Long, photos: PhotoDay<Media>?, isToday: Boolean, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalLook.current.cellCorner)
    val number = LocalDate.ofEpochDay(day).dayOfMonth.toString()
    Box(
        Modifier.aspectRatio(1f)
            .clip(shape)
            .then(if (isToday) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .then(if (photos != null) Modifier.clickable(onClickLabel = "Show in Photos", onClick = onTap) else Modifier)
            // One announcement per day, rather than its number and count read out separately
            .clearAndSetSemantics { contentDescription = (if (isToday) "Today, " else "") + dayDescription(day, photos?.count ?: 0) },
        contentAlignment = Alignment.Center,
    ) {
        if (photos == null) {
            Text(number, color = if (isToday) colors.primary else colors.outline, style = MaterialTheme.typography.bodyMedium)
        } else {
            AsyncImage(Thumb(photos.first.uri), null, Modifier.fillMaxSize().background(colors.surfaceVariant), contentScale = ContentScale.Crop)
            val badge = MaterialTheme.typography.labelSmall.copy(shadow = badgeShadow)
            Text(number, Modifier.align(Alignment.TopStart).padding(4.dp), color = Color.White, style = badge)
            Text("${photos.count}", Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Color.White, style = badge)
        }
    }
}
