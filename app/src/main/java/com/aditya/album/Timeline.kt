package com.aditya.album

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.core.content.edit
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** From this column count up, the grid's headers are months instead of days. */
const val MONTH_COLUMNS = 7

/** Column counts you can pinch between. */
private val columnSteps = listOf(2, 3, 4, 5, MONTH_COLUMNS)
const val DEFAULT_COLUMNS = 4

class Section<T>(val key: Long, val label: String, val items: List<T>)

/** Groups newest-first [media] into day sections (or month sections), labelled like the OPPO gallery. */
fun <T> sections(media: List<T>, byMonth: Boolean, today: Long = LocalDate.now().toEpochDay(), dayOf: (T) -> Long): List<Section<T>> {
    val thisYear = LocalDate.ofEpochDay(today).year
    val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM")
    val oldDayFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
    val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")
    fun keyOf(m: T) = sectionKey(dayOf(m), byMonth)
    fun labelOf(m: T): String {
        val day = dayOf(m)
        val date = LocalDate.ofEpochDay(day)
        return when {
            byMonth -> date.format(monthFormat)
            day == today -> "Today"
            day == today - 1 -> "Yesterday"
            date.year == thisYear -> date.format(dayFormat)
            else -> date.format(oldDayFormat)
        }
    }
    val out = ArrayList<Section<T>>()
    var start = 0
    for (i in 1..media.size) {
        if (i == media.size || keyOf(media[i]) != keyOf(media[start])) {
            out += Section(keyOf(media[start]), labelOf(media[start]), media.subList(start, i))
            start = i
        }
    }
    return out
}

private fun sectionKey(day: Long, byMonth: Boolean) = if (byMonth) LocalDate.ofEpochDay(day).let { it.year * 12L + it.monthValue } else day

/**
 * Maps a flat grid position (each section is a header item followed by its cells) back to its section and photo.
 * Built once per sections change, so the fast scroller and drag-select can look up positions every frame in O(log n).
 */
class GridIndex(sectionSizes: List<Int>) {
    private val headers = IntArray(sectionSizes.size) // grid position of each section's header

    init {
        var at = 0
        sectionSizes.forEachIndexed { i, size ->
            headers[i] = at
            at += size + 1
        }
    }

    /** The section that grid position [position] (its header or one of its cells) belongs to. */
    fun sectionAt(position: Int): Int = headers.binarySearch(position).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)

    /** Index into the flat photo list of the cell at grid position [position]. */
    fun mediaAt(position: Int): Int = position - sectionAt(position) - 1
}

/**
 * Grid position of the header of the section holding [day] (its month's when [byMonth]) in a grid of newest-first
 * [media] laid out like [MediaGrid], or null if no photo is in that section. Lets the calendar scroll Photos to a day.
 */
fun <T> headerPosition(media: List<T>, day: Long, byMonth: Boolean, dayOf: (T) -> Long): Int? {
    val target = sectionKey(day, byMonth)
    var headers = 0
    var previous: Long? = null
    media.forEachIndexed { i, m ->
        val key = sectionKey(dayOf(m), byMonth)
        if (key != previous) {
            if (key == target) return i + headers
            headers++
            previous = key
        }
    }
    return null
}

/**
 * The selection while drag-selecting from [from] to [to] (indexes into [items], either order): [base] plus that
 * whole range, or minus it when [adding] is false. Recomputed from [base] each time, so dragging back shrinks it.
 */
fun <T, K> rangeSelection(base: Set<K>, items: List<T>, from: Int, to: Int, adding: Boolean, keyOf: (T) -> K): Set<K> {
    val range = items.subList(minOf(from, to), maxOf(from, to) + 1).mapTo(HashSet(), keyOf)
    return if (adding) base + range else base - range
}

/** Tapping a date header or Select all: selects all of [ids], or clears them if they were all selected already. */
fun <K> toggleAll(selected: Set<K>, ids: Collection<K>): Set<K> =
    if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids

/** Auto-scroll strength for a finger at [y]: -1..0 within [zone] of [top], 0..1 within [zone] of [bottom], else 0. */
fun edgeScroll(y: Float, top: Float, bottom: Float, zone: Float): Float = when {
    y < top + zone -> -((top + zone - y) / zone).coerceAtMost(1f)
    y > bottom - zone -> ((y - bottom + zone) / zone).coerceAtMost(1f)
    else -> 0f
}

/** A column count that survives app restarts, stored under [key] (Photos and albums keep their own zoom level). */
@Composable
fun rememberColumns(key: String): MutableIntState {
    val prefs = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val columns = remember { mutableIntStateOf(prefs.getInt(key, DEFAULT_COLUMNS)) }
    LaunchedEffect(columns) { snapshotFlow { columns.intValue }.collect { prefs.edit { putInt(key, it) } } }
    return columns
}

/**
 * The photo grid used by Photos and by every album.
 * [columns] and [state] are owned by the caller so scroll position and zoom level survive opening a photo.
 * Long-press and drag selects a range; [onSelect] receives the whole new selection.
 */
@Composable
fun MediaGrid(
    media: List<Media>,
    columns: MutableIntState,
    state: LazyGridState,
    selected: Set<Long>,
    onOpen: (Media) -> Unit,
    onSelect: (Set<Long>) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val spec = LocalLook.current
    val haptics = LocalHapticFeedback.current
    val byMonth = columns.intValue >= MONTH_COLUMNS
    val sections = remember(media, byMonth) { sections(media, byMonth) { it.day } }
    val index = remember(sections) { GridIndex(sections.map { it.items.size }) }
    val selecting = selected.isNotEmpty()
    val toggle = { m: Media -> onSelect(if (m.id in selected) selected - m.id else selected + m.id) }
    val startPadding = with(LocalDensity.current) { contentPadding.calculateStartPadding(LocalLayoutDirection.current).roundToPx() }
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.intValue),
            state = state,
            modifier = Modifier.fillMaxSize()
                .pinchToZoom(columns) { zoomIn ->
                    val i = columnSteps.indexOf(columns.intValue).coerceAtLeast(0)
                    val next = columnSteps[(if (zoomIn) i - 1 else i + 1).coerceIn(0, columnSteps.lastIndex)]
                    if (next != columns.intValue) {
                        columns.intValue = next
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
                .dragToSelect(state, media, index, startPadding, rememberUpdatedState(selected), rememberUpdatedState(onSelect), haptics),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spec.gridGap),
            verticalArrangement = Arrangement.spacedBy(spec.gridGap),
        ) {
            sections.forEach { section ->
                item(key = "h${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Header(section.label, selecting, checked = selecting && section.items.all { it.id in selected }) {
                        CrashLog.note(if (byMonth) "toggle month" else "toggle day")
                        onSelect(toggleAll(selected, section.items.map { it.id }))
                    }
                }
                items(section.items, key = { it.id }, contentType = { "cell" }) { m ->
                    Cell(m, small = columns.intValue >= 5, isSelected = m.id in selected, selecting, onOpen, toggle)
                }
            }
        }
        FastScroller(state, columns.intValue, contentPadding, Modifier.align(Alignment.TopEnd)) { sections[index.sectionAt(it)].label }
    }
}

/** Date header. While selecting, it gets a check circle and tapping it selects or clears the whole day (or month). */
@Composable
private fun Header(label: String, selecting: Boolean, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (selecting) Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() }) else Modifier)
            .padding(start = 16.dp, end = 6.dp, top = 20.dp, bottom = 8.dp), // end lines the check up with the cells' checks
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (selecting) Check(checked, ring = MaterialTheme.colorScheme.outline)
    }
}

/** Selection mark used by every grid: a filled check when selected, an empty ring when not. */
@Composable
internal fun Check(checked: Boolean, ring: Color, modifier: Modifier = Modifier) {
    val circle = modifier.size(20.dp)
    if (checked) {
        Icon(Icons.Filled.CheckCircle, "Selected", circle.background(Color.White, CircleShape), tint = MaterialTheme.colorScheme.primary)
    } else {
        Box(circle.border(1.5.dp, ring, CircleShape))
    }
}

internal val badgeShadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)

@Composable
private fun Cell(m: Media, small: Boolean, isSelected: Boolean, selecting: Boolean, onOpen: (Media) -> Unit, onToggle: (Media) -> Unit) {
    val spec = LocalLook.current
    Box(
        Modifier.aspectRatio(1f)
            .clip(RoundedCornerShape(spec.cellCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { if (selecting) onToggle(m) else onOpen(m) }
            // Touch long-press is handled by the grid's dragToSelect; this keeps it for TalkBack and switch access.
            .semantics { onLongClick("Select") { onToggle(m); true } },
    ) {
        AsyncImage(Thumb(m.uri), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (m.isVideo && !small) {
            Text(
                duration(m.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(shadow = badgeShadow),
            )
        }
        if (m.isFavorite && !small) {
            Icon(Icons.Filled.Favorite, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(14.dp), tint = Color.White)
        }
        if (selecting) {
            if (isSelected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            Check(isSelected, ring = Color.White, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Two-finger pinch moves the column count one step per gesture (like Google Photos / OPPO).
 * Watches the Initial pass and consumes the events, so the grid doesn't scroll during a pinch.
 */
private fun Modifier.pinchToZoom(key: Any, onStep: (zoomIn: Boolean) -> Unit) = pointerInput(key) {
    awaitEachGesture {
        var scale = 1f
        var stepped = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                event.changes.forEach { it.consume() }
                if (!stepped) {
                    scale *= event.calculateZoom()
                    if (scale > 1.3f || scale < 0.77f) {
                        onStep(scale > 1f)
                        stepped = true
                    }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Long-press a photo to select it, then keep dragging to select every photo between it and the finger, in reading
 * order. Dragging back shrinks the range, and holding near the top or bottom edge scrolls. Starting on a photo that
 * is already selected clears the range instead, just as a long-press on its own toggles one photo.
 *
 * Built on awaitLongPressOrCancellation rather than detectDragGesturesAfterLongPress: that consumes in the Main pass,
 * which reaches the cells and the grid's own scrolling first, so lifting after a long-press would also tap the photo
 * (unselecting it again) and a quick drag could scroll the grid. Consuming in the Initial pass keeps the gesture ours.
 */
private fun Modifier.dragToSelect(
    state: LazyGridState,
    media: List<Media>,
    index: GridIndex,
    startPadding: Int,
    selected: State<Set<Long>>,
    onSelect: State<(Set<Long>) -> Unit>,
    haptics: HapticFeedback,
) = pointerInput(state, media, index, startPadding) {
    val zone = 64.dp.toPx()
    val maxSpeed = 1500.dp.toPx() // per second, with the finger right at the edge
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val anchor = state.cellAt(press.position, startPadding) ?: return@awaitEachGesture
            val base = selected.value
            val adding = media[index.mediaAt(anchor)].id !in base
            var finger = press.position
            var moved = false // no auto-scroll just because the long-pressed photo sits near an edge
            var current = -1
            fun extend() {
                val cell = state.cellAt(finger, startPadding) ?: return // over a header or a gap: keep the range
                if (cell == current) return
                haptics.performHapticFeedback(if (current < 0) HapticFeedbackType.LongPress else HapticFeedbackType.SegmentFrequentTick)
                current = cell
                onSelect.value(rangeSelection(base, media, index.mediaAt(anchor), index.mediaAt(cell), adding) { it.id })
            }
            extend()
            CrashLog.note("drag select")
            val scroller = launch {
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val info = state.layoutInfo
                    val bottom = info.viewportSize.height - info.afterContentPadding
                    val speed = if (moved) edgeScroll(finger.y, info.beforeContentPadding.toFloat(), bottom.toFloat(), zone) else 0f
                    if (speed != 0f) {
                        state.scrollBy(speed * maxSpeed * (now - last) / 1e9f)
                        extend() // the photos moved under a still finger
                    }
                    last = now
                }
            }
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == press.id } ?: break
                    if (change.isConsumed) break // a second finger turned it into a pinch
                    change.consume()
                    if (!change.pressed) break
                    finger = change.position
                    moved = moved || (finger - press.position).getDistance() > viewConfiguration.touchSlop
                    extend()
                }
            } finally {
                scroller.cancel()
            }
        }
    }
}

/** Grid position of the photo under [point] (grid coordinates), or null over a header or a gap. */
private fun LazyGridState.cellAt(point: Offset, startPadding: Int): Int? {
    // Item offsets don't include the content padding the grid draws them after
    val p = point.round() - IntOffset(startPadding, layoutInfo.beforeContentPadding)
    return layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long && IntRect(it.offset, it.size).contains(p) }?.index
}
