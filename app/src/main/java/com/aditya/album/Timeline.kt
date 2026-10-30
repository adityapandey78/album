package com.aditya.album

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.core.content.edit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** From this column count up, the grid's headers are months instead of days: the zoom level after 7 columns. */
const val MONTH_COLUMNS = 9

/** Column counts you can pinch between: 3, 4, 5 and 7 photos a row by day, then months. */
private val columnSteps = intArrayOf(3, 4, 5, 7, MONTH_COLUMNS)
const val DEFAULT_COLUMNS = 4

/** [count] moved to the nearest pinch step, so a count saved by an older version (2 columns) still lands on one. */
fun snapColumns(count: Int): Int = columnSteps.minBy { abs(it - count) }

/** The column count one pinch step from [count]: fewer, bigger photos when [zoomIn]. Stays put at either end. */
fun stepColumns(count: Int, zoomIn: Boolean): Int {
    val i = columnSteps.indexOf(snapColumns(count))
    return columnSteps[(if (zoomIn) i - 1 else i + 1).coerceIn(0, columnSteps.lastIndex)]
}

class Section<T>(val key: Long, val label: String, val items: List<T>)

/** Groups newest-first [media] into day sections ("Today", "Sat, 24 Oct", the year when not this one) or month sections. */
fun <T> sections(media: List<T>, byMonth: Boolean, today: Long = LocalDate.now().toEpochDay(), dayOf: (T) -> Long): List<Section<T>> {
    val thisYear = LocalDate.ofEpochDay(today).year
    val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM")
    val oldDayFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
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

/** A day's place under its date, as "Bengaluru, Karnataka": the town and its state, either alone when the other is unknown. */
fun placeLine(place: Place): String? = listOf(place.city, place.admin1).filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { null }

/** A section's key: the epoch day, or year * 12 + month in month view. Newer sections have bigger keys. */
private fun sectionKey(day: Long, byMonth: Boolean) = if (byMonth) LocalDate.ofEpochDay(day).let { it.year * 12L + it.monthValue } else day

// ---------- Layout: what each lazy item shows ----------

/** What one lazy item of [MediaGrid] shows. */
enum class Slot { Top, Header, Cell, Row, Feature }

/**
 * One lazy item of [MediaGrid]: the caller's header ([Slot.Top]), a date header, one square cell, a justified row, or a
 * mosaic block (the section's first photo at 2x2 with the photos beside it). [from] until [to] index the photos it
 * shows; a date header covers its whole section. [fill] is how much of a short last justified row stays empty, in
 * units of photo shape (width over height), so that row keeps a normal height instead of stretching.
 */
class GridItem(val slot: Slot, val section: Int, val from: Int, val to: Int, val fill: Float = 0f)

/**
 * [MediaGrid]'s lazy items for one photo list, column count and gallery style, with the lookups that map a grid
 * position back to photos and a date to its header. Grid positions are indexes into [items].
 */
class GridLayout(val items: List<GridItem>, val columns: Int, private val sectionKeys: LongArray) {
    private val headers = IntArray(sectionKeys.size) // grid position of each section's header

    init {
        items.forEachIndexed { i, it -> if (it.slot == Slot.Header) headers[it.section] = i }
    }

    /**
     * Grid position of the header of the section with [key] (see [sectionKey]), or of the next older section when none
     * has it (a calendar day without photos lands on the photos before it). Null only when there are no photos.
     */
    fun headerOf(key: Long): Int? {
        if (sectionKeys.isEmpty()) return null
        // Keys run newest (biggest) first: find the first one at or below [key]
        var lo = 0
        var hi = sectionKeys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sectionKeys[mid] <= key) hi = mid else lo = mid + 1
        }
        return headers[lo.coerceAtMost(sectionKeys.lastIndex)]
    }

    /** The lazy item key of grid position [i]; [idOf] gives the media id of a photo index. Stable across zoom levels for cells. */
    fun keyOf(i: Int, idOf: (Int) -> Long): Any {
        val item = items[i]
        return when (item.slot) {
            Slot.Top -> "top"
            Slot.Header -> "h${sectionKeys[item.section]}"
            Slot.Cell -> idOf(item.from)
            Slot.Row -> "r${idOf(item.from)}"
            Slot.Feature -> "f${idOf(item.from)}"
        }
    }
}

/**
 * Justified rows stop growing past this many columns' worth of photo shape. A bit over 1, so rows come out close to
 * [columns] square photos tall on average instead of always shorter.
 */
private const val ROW_SLACK = 1.15f

/** A short last row under this share of a full one keeps a normal height instead of stretching to the full width. */
private const val SHORT_ROW = 0.8f

/** A photo's shape for justified rows, width over height: 1 when unknown, kept between 1:2 and 3:1 so a long screenshot or a 360 panorama never makes a sliver. */
fun shapeOf(m: Media): Float = if (m.width > 0 && m.height > 0) (m.width.toFloat() / m.height).coerceIn(0.5f, 3f) else 1f

/** How many photos a mosaic block holds: the 2x2 one plus two rows beside it. */
private fun featureSize(columns: Int) = 1 + 2 * (columns - 2)

/**
 * Lays [sections] out as [MediaGrid]'s lazy items: a date header per section, then per [style] one item per square
 * cell (Grid), one per row that keeps each photo's [shapeOf] (Justified, greedy rows of about [columns] squares'
 * width), or one 2x2 block for the section's first photo and its neighbours followed by square cells (Mosaic). [top]
 * puts the caller's header first.
 */
fun <T> gridLayout(sections: List<Section<T>>, columns: Int, style: GalleryStyle, top: Boolean, shapeOf: (T) -> Float): GridLayout {
    val items = ArrayList<GridItem>(sections.sumOf { it.items.size } + sections.size + 1)
    if (top) items += GridItem(Slot.Top, 0, 0, 0)
    var base = 0 // index of the section's first photo in the whole list
    sections.forEachIndexed { s, section ->
        val n = section.items.size
        items += GridItem(Slot.Header, s, base, base + n)
        when (style) {
            GalleryStyle.Grid -> for (i in 0 until n) items += GridItem(Slot.Cell, s, base + i, base + i + 1)
            GalleryStyle.Mosaic -> {
                val block = minOf(n, featureSize(columns))
                items += GridItem(Slot.Feature, s, base, base + block)
                for (i in block until n) items += GridItem(Slot.Cell, s, base + i, base + i + 1)
            }
            GalleryStyle.Justified -> {
                var start = 0
                var sum = 0f
                for (i in 0 until n) {
                    val shape = shapeOf(section.items[i])
                    if (i > start && sum + shape > columns * ROW_SLACK) {
                        items += GridItem(Slot.Row, s, base + start, base + i)
                        start = i
                        sum = 0f
                    }
                    sum += shape
                }
                if (n > start) items += GridItem(Slot.Row, s, base + start, base + n, fill = if (sum < columns * SHORT_ROW) columns - sum else 0f)
            }
        }
        base += n
    }
    return GridLayout(items, columns, LongArray(sections.size) { sections[it].key })
}

// ---------- Geometry of rows and blocks ----------

/** Widths of the grid's [columns] cells across [width] px with [gutter] px between, split the way LazyVerticalGrid splits them (spare pixels go to the first cells). */
fun cellWidths(width: Int, columns: Int, gutter: Int): IntArray {
    val free = (width - gutter * (columns - 1)).coerceAtLeast(0)
    return IntArray(columns) { free / columns + if (it < free % columns) 1 else 0 }
}

/**
 * Where each photo of a Row or Feature [item] sits inside it, in px, for an item [width] px wide, in photo order (left to
 * right, then top to bottom). [shapeAt] gives a photo's shape by its index. One source for drawing, drag-select hit
 * tests and the viewer's fly-in, so they always agree.
 */
fun tileFrames(item: GridItem, width: Int, gutter: Int, columns: Int, shapeAt: (Int) -> Float): List<IntRect> = when (item.slot) {
    Slot.Row -> {
        val n = item.to - item.from
        val height = rowHeight(item, width, gutter, shapeAt)
        var x = 0f
        List(n) { k ->
            val w = shapeAt(item.from + k) * height
            val left = x.roundToInt()
            // A full row ends exactly at the edge, whatever the rounding
            val right = if (k == n - 1 && item.fill == 0f) width else (x + w).roundToInt()
            x += w + gutter
            IntRect(left, 0, right, height.roundToInt())
        }
    }
    Slot.Feature -> {
        // Columns match the plain cells below the block pixel for pixel
        val widths = cellWidths(width, columns, gutter)
        val lefts = IntArray(columns)
        for (i in 1 until columns) lefts[i] = lefts[i - 1] + widths[i - 1] + gutter
        val line = widths[0] + gutter // a square cell row and the gap under it
        val beside = columns - 2
        List(item.to - item.from) { k ->
            if (k == 0) {
                IntRect(0, 0, lefts[1] + widths[1], line + widths[0])
            } else {
                val col = 2 + (k - 1) % beside
                val top = (k - 1) / beside * line
                IntRect(lefts[col], top, lefts[col] + widths[col], top + widths[0])
            }
        }
    }
    else -> emptyList()
}

/** Height in px of a Row or Feature [item] [width] px wide, as [tileFrames] lays it out, without building the frames. */
fun itemHeight(item: GridItem, width: Int, gutter: Int, columns: Int, shapeAt: (Int) -> Float): Int = when (item.slot) {
    Slot.Row -> rowHeight(item, width, gutter, shapeAt).roundToInt()
    Slot.Feature -> cellWidths(width, columns, gutter)[0] * 2 + gutter
    else -> 0
}

/** Every photo of a justified row shares this height: the width left after the gaps, over the row's total shape. */
private fun rowHeight(item: GridItem, width: Int, gutter: Int, shapeAt: (Int) -> Float): Float {
    var shapes = item.fill
    for (i in item.from until item.to) shapes += shapeAt(i)
    return (width - (item.to - item.from - 1) * gutter).coerceAtLeast(0) / shapes
}

/**
 * The scrubber's estimate of every lazy item's height in px (see [ScrollMap]), from the layout instead of measuring:
 * a line of cells counts once, on its last cell, so every cell of a line starts at the line's top. [top] and [header]
 * are the heights of the caller's header and a date header ([firstHeader] for the first, which has less room above);
 * [headerExtra] adds to a section's header (its place line).
 */
fun estimateHeights(
    layout: GridLayout,
    width: Int,
    gutter: Int,
    top: Int,
    header: Int,
    firstHeader: Int,
    shapeAt: (Int) -> Float,
    headerExtra: (section: Int) -> Int = { 0 },
): IntArray {
    val cell = cellWidths(width, layout.columns, gutter)[0]
    var run = 0 // cells so far in the current run of cells
    return IntArray(layout.items.size) { i ->
        val item = layout.items[i]
        if (item.slot != Slot.Cell) run = 0
        when (item.slot) {
            Slot.Top -> top + gutter
            Slot.Header -> (if (item.section == 0) firstHeader else header) + headerExtra(item.section) + gutter
            Slot.Row, Slot.Feature -> itemHeight(item, width, gutter, layout.columns, shapeAt) + gutter
            Slot.Cell -> {
                run++
                val lineEnds = run % layout.columns == 0 || layout.items.getOrNull(i + 1)?.slot != Slot.Cell
                if (lineEnds) cell + gutter else 0
            }
        }
    }
}

/** The month (year * 12 + month - 1) of every lazy item: its section's month. */
fun <T> itemMonths(layout: GridLayout, sections: List<Section<T>>, dayOf: (T) -> Long): IntArray {
    val months = IntArray(sections.size) { s -> LocalDate.ofEpochDay(dayOf(sections[s].items[0])).let { it.year * 12 + it.monthValue - 1 } }
    return IntArray(layout.items.size) { months.getOrElse(layout.items[it].section) { 0 } }
}

// ---------- Selection helpers ----------

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

/** A column count that survives app restarts, stored under [key] (Library and albums keep their own zoom level). */
@Composable
fun rememberColumns(key: String): MutableIntState {
    val prefs = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val columns = remember { mutableIntStateOf(snapColumns(prefs.getInt(key, DEFAULT_COLUMNS))) }
    LaunchedEffect(columns) { snapshotFlow { columns.intValue }.collect { prefs.edit { putInt(key, it) } } }
    return columns
}

// ---------- Where a photo is on screen ----------

/** What [cellBounds] needs to find a photo inside a row: the layout and photos a grid state was last composed with. */
private class Placement(val layout: GridLayout, val media: List<Media>, val gutter: Int)

// Weak keys: an entry goes with its grid state. Only touched on the main thread (composition and the viewer's callbacks).
private val placements = WeakHashMap<LazyGridState, Placement>()

/**
 * Where photo [mediaId] sits in the [MediaGrid] that [state] drives, in the grid's own coordinates (add the grid's
 * window position for the viewer's fly-in), or null when it isn't on screen. Justified rows and Mosaic blocks hold
 * several photos per lazy item, so looking a photo up by item key alone only finds Grid cells.
 */
fun cellBounds(state: LazyGridState, mediaId: Long): Rect? {
    val info = state.layoutInfo
    // Item offsets start after the top content padding; viewportStartOffset is minus that padding
    fun bounds(offset: IntOffset, frame: IntRect) =
        Rect(Offset((offset.x + frame.left).toFloat(), (offset.y - info.viewportStartOffset + frame.top).toFloat()), Size(frame.width.toFloat(), frame.height.toFloat()))
    info.visibleItemsInfo.find { it.key == mediaId }?.let { return bounds(it.offset, IntRect(IntOffset.Zero, it.size)) }
    val placement = placements[state] ?: return null
    for (visible in info.visibleItemsInfo) {
        val item = placement.layout.items.getOrNull(visible.index) ?: continue
        if (item.slot != Slot.Row && item.slot != Slot.Feature) continue
        if (item.to > placement.media.size || visible.key != placement.layout.keyOf(visible.index) { placement.media[it].id }) continue // laid out from an older list
        val k = (item.from until item.to).indexOfFirst { placement.media[it].id == mediaId }
        if (k < 0) continue
        val frames = tileFrames(item, visible.size.width, placement.gutter, placement.layout.columns) { shapeOf(placement.media[it]) }
        return bounds(visible.offset, frames[k])
    }
    return null
}

// ---------- The grid ----------

/** The scrubber starts this far below the top of a grid with a title: 15dp under the collapsed title bar. */
private val scrubberTop = CollapsedBarHeight + 15.dp

/** Where a calendar jump leaves the day's header: its top just under the collapsed title bar, so the date is readable. */
private val jumpGap = 72.dp

/** How long the column count shows after a pinch step. */
private const val ZOOM_NOTE_MS = 900L

/**
 * The photo grid of the Library and every album, in the gallery style chosen in Settings > Appearance.
 * [columns] and [state] are owned by the caller so scroll position and zoom level survive opening a photo; use
 * [cellBounds] to find a photo's cell on screen. Long-press and drag selects a range; [onSelect] receives the whole new
 * selection. [header] scrolls with the photos above the first date (the screen's LargeTopBar).
 * [jumpTo] scrolls to that epoch day's date header (or its month's) and makes it glow once, as after a calendar jump;
 * [onJumped] runs once that's done so the caller can clear it, which keeps the jump from repeating later. [bar] is drawn
 * over the grid (the screen's CollapsingBar), under the column count a pinch shows. Each day's header names where its
 * newest placed photo was taken.
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
    header: (@Composable () -> Unit)? = null,
    jumpTo: Long? = null,
    onJumped: () -> Unit = {},
    selectMode: Boolean = false, // the top bar's Select: checks show before anything is picked
    badge: ((Media) -> String?)? = null, // a badge on every tile, small ones too (Trash's days left); else the photo's own
    bar: (@Composable () -> Unit)? = null,
) {
    val spec = AlbumTheme.spec
    val type = AlbumTheme.type
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val cols = columns.intValue
    val byMonth = cols >= MONTH_COLUMNS
    val sections = remember(media, byMonth) { sections(media, byMonth) { it.day } }
    // The place of each day's newest photo that has one; months don't get one, they span too many
    val placeOf = rememberPlaces()
    val places = remember(sections, placeOf) {
        if (byMonth || placeOf.isEmpty()) emptyList()
        else sections.map { s -> s.items.firstNotNullOfOrNull { placeOf[it.id] }?.let(::placeLine) }
    }
    val layout = remember(sections, cols, spec.appearance.gallery, header != null) {
        gridLayout(sections, cols, spec.appearance.gallery, top = header != null, ::shapeOf)
    }
    val gutter = with(density) { spec.gutter.roundToPx() }
    val shapeAt = remember(media) { { i: Int -> shapeOf(media[i]) } }
    val idOf = remember(media) { { i: Int -> media[i].id } }
    val placement = remember(layout, media, gutter) { Placement(layout, media, gutter) }
    DisposableEffect(state, placement) {
        placements[state] = placement
        onDispose { if (placements[state] === placement) placements.remove(state) }
    }

    val startPadding = with(density) { contentPadding.calculateStartPadding(direction).roundToPx() }
    val endPadding = with(density) { contentPadding.calculateEndPadding(direction).roundToPx() }
    var width by remember { mutableIntStateOf(0) }
    val itemWidth = (width - startPadding - endPadding).coerceAtLeast(0)
    val scrollMap = remember(layout, itemWidth, gutter, density, type, places) {
        with(density) {
            val title = type.titleMd.lineHeight.roundToPx()
            val placeLine = type.caption.lineHeight.roundToPx()
            // ponytail: the caller's header is guessed as a LargeTopBar (actions row, title, subtitle); a wrong guess only
            // nudges the scrubber near the top. Measuring it would need a remeasure pass for one item.
            val top = (Space.s2 + Space.target + Space.s6 + Space.s1 + Space.s3).roundToPx() + type.titleXl.lineHeight.roundToPx() + type.meta.lineHeight.roundToPx()
            ScrollMap(
                estimateHeights(
                    layout, itemWidth, gutter, top, title + (Space.s8 + Space.s3).roundToPx(), title + (Space.s2 + Space.s3).roundToPx(), shapeAt,
                ) { section -> if (places.getOrNull(section) != null) placeLine else 0 },
                itemMonths(layout, sections) { it.day },
            )
        }
    }

    // A calendar jump: scroll its date header to the top, then let it glow flash-soft once
    var pulse by remember { mutableStateOf<Long?>(null) } // key of the section whose header glows
    val glow = remember { Animatable(0f) }
    val latestLayout by rememberUpdatedState(layout)
    val latestSections by rememberUpdatedState(sections)
    val latestOnJumped by rememberUpdatedState(onJumped)
    LaunchedEffect(jumpTo) {
        val day = jumpTo ?: return@LaunchedEffect
        val target = latestLayout
        target.headerOf(sectionKey(day, target.columns >= MONTH_COLUMNS))?.let { position ->
            state.requestScrollToItem(position, -with(density) { jumpGap.roundToPx() })
            pulse = latestSections[target.items[position].section].key
        }
        CrashLog.note("jump to date")
        latestOnJumped()
    }
    LaunchedEffect(pulse) {
        if (pulse == null) return@LaunchedEffect
        // As in the design: solid for the first 45% of 1.4 s, then fading out. A colour, not movement, so it stays
        // with Remove animations on, only without the fade.
        glow.snapTo(1f)
        delay(630)
        if (spec.still) glow.snapTo(0f) else glow.animateTo(0f, tween(770))
        pulse = null
    }

    // Each pinch step shows the new column count for a moment; a new step restarts it
    var zoomNote by remember { mutableStateOf<ZoomNote?>(null) }
    LaunchedEffect(zoomNote) {
        if (zoomNote == null) return@LaunchedEffect
        delay(ZOOM_NOTE_MS)
        zoomNote = null
    }

    val selecting = selectMode || selected.isNotEmpty()
    val small = cols >= 5
    val toggle = { m: Media -> onSelect(if (m.id in selected) selected - m.id else selected + m.id) }
    fun badgeOf(m: Media, small: Boolean) = badge?.invoke(m) ?: if (small) null else traitBadge(m)
    val latestHaptics by rememberUpdatedState(haptics)
    // Test tags double as resource ids so the benchmarks (UiAutomator) can find the grid, tiles and the scrubber
    Box(modifier.fillMaxSize().onSizeChanged { width = it.width }.semantics { testTagsAsResourceId = true }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = state,
            modifier = Modifier.fillMaxSize()
                .testTag("media_grid")
                .pinchToZoom(columns) { zoomIn ->
                    val next = stepColumns(columns.intValue, zoomIn)
                    if (next != columns.intValue) {
                        columns.intValue = next
                        latestHaptics.tick()
                        zoomNote = ZoomNote(next)
                    }
                }
                .dragToSelect(state, media, layout, gutter, startPadding, shapeAt, rememberUpdatedState(selected), rememberUpdatedState(onSelect), haptics),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spec.gutter),
            verticalArrangement = Arrangement.spacedBy(spec.gutter),
        ) {
            // One interval for the whole grid: the layout already knows every item, so nothing is rebuilt per section
            items(
                count = layout.items.size,
                key = { i -> layout.keyOf(i, idOf) },
                span = { i -> if (layout.items[i].slot == Slot.Cell) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
                contentType = { i -> layout.items[i].slot },
            ) { i ->
                val item = layout.items[i]
                when (item.slot) {
                    Slot.Top -> header?.invoke()
                    Slot.Header -> {
                        val section = sections[item.section]
                        DateHeader(
                            section.label,
                            places.getOrNull(item.section),
                            first = item.section == 0,
                            selecting = selecting,
                            checked = selecting && section.items.all { it.id in selected },
                            glow = if (section.key == pulse) ({ glow.value }) else null,
                        ) {
                            CrashLog.note(if (byMonth) "toggle month" else "toggle day")
                            onSelect(toggleAll(selected, section.items.map { it.id }))
                        }
                    }
                    Slot.Cell -> GridTile(media[item.from], small, checkSizeFor(cols), selecting, media[item.from].id in selected, onOpen, toggle, badgeOf(media[item.from], small), Modifier.aspectRatio(1f))
                    Slot.Row, Slot.Feature -> TileRow(item, cols, gutter, shapeAt) { index, big ->
                        // Rows are reused for other rows as the grid scrolls; the key keeps a tile's selection animation its own
                        // a Mosaic 2x2 tile is as big as one at half the columns, so its check is too
                        key(media[index].id) { GridTile(media[index], small && !big, checkSizeFor(if (big) cols / 2 else cols), selecting, media[index].id in selected, onOpen, toggle, badgeOf(media[index], small && !big)) }
                    }
                }
            }
        }
        FastScroller(state, scrollMap, contentPadding, Modifier.align(Alignment.TopEnd), topInset = if (header != null) scrubberTop else 0.dp)
        bar?.invoke()
        zoomNote?.let { note ->
            key(note) { ZoomNotePill(note.columns, Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding() + Space.s3)) }
        }
    }
}

/** One pinch step's note; a new instance per step, so the same count twice still restarts it. */
private class ZoomNote(val columns: Int)

/** The column count after a pinch step: a small pill at the top that pops in, gone after [ZOOM_NOTE_MS]. */
@Composable
private fun ZoomNotePill(columns: Int, modifier: Modifier) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val pop = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(220, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.3f))) }
    Row(
        modifier
            .graphicsLayer {
                scaleX = 0.8f + 0.2f * pop.value
                scaleY = scaleX
                alpha = pop.value.coerceIn(0f, 1f)
            }
            .floating(CircleShape).background(c.surfaceHigh, CircleShape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Space.s4, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Pinch, null, size = Space.iconSm, tint = c.ink)
        Text("$columns columns", style = AlbumTheme.type.meta, color = c.ink)
    }
}

/**
 * Date header: the day ("Today", "Sat, 24 Oct") or month in title-md, and under it in a caption the day's [place]. While
 * selecting, a check on the right, level with the bottom line, and the whole header select or clear that day (or month).
 * [glow] (0..1) tints it flash-soft after a calendar jump.
 */
@Composable
private fun DateHeader(label: String, place: String?, first: Boolean, selecting: Boolean, checked: Boolean, glow: (() -> Float)?, onToggle: () -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (glow != null) Modifier.drawBehind { drawRoundRect(c.flashSoft, cornerRadius = CornerRadius(Space.radiusMd.toPx()), alpha = glow()) } else Modifier)
            .then(if (selecting) Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() }) else Modifier)
            .padding(start = Space.s4, end = Space.s4, top = if (first) Space.s2 else Space.s8, bottom = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, Modifier.semantics { heading() }, style = AlbumTheme.type.titleMd, color = c.ink)
            place?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selecting) SelectCheck(checked, onSurface = true)
    }
}

/** A tile in the grid: tap opens it (or toggles it while selecting). */
@Composable
private fun GridTile(
    m: Media,
    small: Boolean,
    checkSize: Dp,
    selecting: Boolean,
    selected: Boolean,
    onOpen: (Media) -> Unit,
    onToggle: (Media) -> Unit,
    badge: String?,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    MediaTile(
        m, selecting, selected,
        modifier.testTag("media_tile")
            // No ripple: the photo dipping while pressed, then opening (or its check popping), is the feedback
            .clickable(interactionSource = source, indication = null) { if (selecting) onToggle(m) else onOpen(m) }
            // Touch long-press is handled by the grid's dragToSelect; this keeps it for TalkBack and switch access.
            .semantics { onLongClick("Select") { onToggle(m); true } },
        small = small,
        badge = badge,
        checkSize = checkSize,
        pressed = pressed,
    )
}

/** A Justified row or Mosaic block: several tiles in one lazy item, placed by [tileFrames]. [tile] gets each photo's index and whether it's the 2x2 one. */
@Composable
private fun TileRow(item: GridItem, columns: Int, gutter: Int, shapeAt: (Int) -> Float, tile: @Composable (index: Int, big: Boolean) -> Unit) {
    Layout(
        content = {
            for (i in item.from until item.to) tile(i, item.slot == Slot.Feature && i == item.from)
        },
    ) { measurables, constraints ->
        val frames = tileFrames(item, constraints.maxWidth, gutter, columns, shapeAt)
        layout(constraints.maxWidth, frames.maxOfOrNull { it.bottom } ?: 0) {
            measurables.forEachIndexed { k, m ->
                val f = frames[k]
                m.measure(Constraints.fixed(f.width, f.height)).place(f.left, f.top)
            }
        }
    }
}

/** Old selection mark, kept for Trash until it moves to [MediaTile]; [ring] is ignored. New code uses [SelectCheck]. */
@Composable
internal fun Check(checked: Boolean, @Suppress("UNUSED_PARAMETER") ring: Color, modifier: Modifier = Modifier) = SelectCheck(checked, modifier)


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
    layout: GridLayout,
    gutter: Int,
    startPadding: Int,
    shapeAt: (Int) -> Float,
    selected: State<Set<Long>>,
    onSelect: State<(Set<Long>) -> Unit>,
    haptics: Haptics,
) = pointerInput(state, media, layout, gutter, startPadding, haptics) {
    val zone = 64.dp.toPx()
    val maxSpeed = 1500.dp.toPx() // per second, with the finger right at the edge
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val anchor = state.photoAt(press.position, startPadding, layout, gutter, shapeAt) ?: return@awaitEachGesture
            val base = selected.value
            val adding = media[anchor].id !in base
            var finger = press.position
            var moved = false // no auto-scroll just because the long-pressed photo sits near an edge
            var current = -1
            fun extend() {
                val photo = state.photoAt(finger, startPadding, layout, gutter, shapeAt) ?: return // over a header or a gap: keep the range
                if (photo == current) return
                if (current < 0) haptics.longPress() else haptics.lightTick()
                current = photo
                onSelect.value(rangeSelection(base, media, anchor, photo, adding) { it.id })
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

/** Index in the photo list of the photo under [point] (grid coordinates), or null over a header, the top or a gap. */
private fun LazyGridState.photoAt(point: Offset, startPadding: Int, layout: GridLayout, gutter: Int, shapeAt: (Int) -> Float): Int? {
    // Item offsets don't include the content padding the grid draws them after
    val p = point.round() - IntOffset(startPadding, layoutInfo.beforeContentPadding)
    val hit = layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(p) } ?: return null
    val item = layout.items.getOrNull(hit.index) ?: return null
    return when (item.slot) {
        Slot.Cell -> item.from
        Slot.Row, Slot.Feature -> {
            val inside = p - hit.offset
            val k = tileFrames(item, hit.size.width, gutter, layout.columns, shapeAt).indexOfFirst { it.contains(inside) }
            if (k < 0) null else item.from + k
        }
        Slot.Top, Slot.Header -> null
    }
}
