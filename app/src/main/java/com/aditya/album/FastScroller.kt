package com.aditya.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The scrubber's model of the grid: where every lazy item starts, estimated in px from the layout instead of measured
 * (see [estimateHeights]), and the month each belongs to. Lets the scrubber map between a scroll position, a thumb
 * position and a month on every frame in O(log n), without measuring rows or allocating.
 */
class ScrollMap(private val heights: IntArray, private val months: IntArray) {
    private val tops = IntArray(heights.size + 1).also { for (i in heights.indices) it[i + 1] = it[i] + heights[i] }

    /** Estimated height of all the grid's content, px. */
    val total: Int get() = tops[heights.size]

    /** Item index where each month starts, newest month first. TalkBack steps the scrubber through these. */
    val monthStarts: IntArray = starts { months[it] }

    /** Item index where each year starts, newest year first: the ticks beside the track. */
    val yearStarts: IntArray = starts { months[it] / 12 }

    private fun starts(of: (Int) -> Int): IntArray =
        months.indices.filter { it == 0 || of(it) != of(it - 1) }.toIntArray()

    /** Estimated distance from the top of the content to item [index], px. */
    fun topOf(index: Int): Int = tops[index.coerceIn(0, heights.size)]

    /** The item [y] px down the content: the last one starting at or above it (for a line of cells, its last cell). */
    fun indexAt(y: Int): Int {
        var lo = 0
        var hi = heights.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }

    /** The month of item [index], as year * 12 + month - 1. */
    fun monthOf(index: Int): Int = if (months.isEmpty()) 0 else months[index.coerceIn(0, months.lastIndex)]

    /** Which of [monthStarts] item [index] falls in. */
    fun monthIndexOf(index: Int): Int {
        var lo = 0
        var hi = monthStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (monthStarts[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }
}

// The design's Scrubber: a 28dp thumb that widens to 32 while dragged, 48dp (target) tall
private val thumbWidth = 28.dp
private val thumbWidthActive = 32.dp
private val thumbHeight = Space.target

/** A year label beside the track while dragging; [at] is how far down the track that year starts (0..1). */
private class Tick(val label: String, val at: Float)

/**
 * Fast scroll for a long timeline: a floating thumb on the right edge that appears once the grid scrolls and fades
 * 1.5 s after it stops, only when there are more than about three screens of photos. Dragging it moves through the
 * whole grid in proportion, with a bubble showing the month and year, a track with year ticks, and a haptic tick per
 * month (a stronger one per year). TalkBack treats it as an adjustable control that steps by month.
 * [topInset] keeps it clear of a title bar drawn over the top of the grid.
 */
@Composable
fun FastScroller(state: LazyGridState, map: ScrollMap, contentPadding: PaddingValues, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val still = AlbumTheme.spec.still
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val thumbPx = with(density) { thumbHeight.toPx() }
    val tickGap = with(density) { Space.s6.toPx() } // year labels closer than this on the track would overlap
    val monthNames = remember { Month.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) } }
    // derivedStateOf: recompose when the answer changes, not on every scrolled frame
    val long by remember(state, map) { derivedStateOf { state.layoutInfo.viewportSize.height.let { it > 0 && map.total > it * 3 } } }
    val monthIndex by remember(state, map) { derivedStateOf { map.monthIndexOf(state.firstVisibleItemIndex) } }
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val active = state.isScrollInProgress || dragging
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(1500)
            shown = false
        }
    }
    var travel by remember { mutableFloatStateOf(0f) } // how far the thumb can move, px
    var thumbY by remember { mutableFloatStateOf(0f) } // thumb top while dragging, px
    var month by remember { mutableIntStateOf(0) } // year * 12 + month - 1 under the thumb while dragging
    var ticks by remember { mutableStateOf(emptyList<Tick>()) }
    val width by animateDpAsState(if (dragging) thumbWidthActive else thumbWidth, if (still) snap() else spring(stiffness = Spring.StiffnessMedium), label = "thumbWidth")

    Box(
        modifier.fillMaxHeight()
            .padding(top = contentPadding.calculateTopPadding() + topInset, bottom = contentPadding.calculateBottomPadding())
            .onSizeChanged { travel = it.height - thumbPx },
    ) {
        AnimatedVisibility(
            shown && long,
            Modifier.fillMaxHeight(),
            enter = if (still) EnterTransition.None else fadeIn(),
            exit = if (still) ExitTransition.None else fadeOut(),
        ) {
            Box(Modifier.fillMaxHeight()) {
                if (dragging) {
                    // The track, centred under the thumb
                    Box(Modifier.align(Alignment.TopEnd).padding(end = Space.s2 + thumbWidthActive / 2 - 1.dp).width(2.dp).fillMaxHeight().background(c.line, CircleShape))
                    ticks.forEach { tick ->
                        Text(
                            tick.label,
                            Modifier.align(Alignment.TopEnd).padding(end = Space.target + Space.s1).layout { measurable, constraints ->
                                val p = measurable.measure(constraints)
                                // Level with the thumb's centre when the thumb is at that year
                                layout(p.width, p.height) { p.place(0, (tick.at * travel + (thumbPx - p.height) / 2).roundToInt()) }
                            },
                            style = type.meta,
                            color = c.inkMuted,
                        )
                    }
                }
                Row(
                    // Read in the layout phase, so scrolling moves the thumb without recomposing
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (if (dragging) thumbY else state.progress(map) * travel).roundToInt()) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dragging) Bubble(monthNames[month % 12], "${month / 12}")
                    val months = map.monthStarts.size
                    val current = monthIndex
                    Box(
                        Modifier.size(Space.target) // the touch target around the narrower thumb
                            .testTag("fast_scroller")
                            // Keeps the back gesture off the thumb. Only while it rests: moving the exclusion every scrolled
                            // frame would send the window manager an update per frame.
                            .then(if (!active) Modifier.systemGestureExclusion() else Modifier)
                            .semantics {
                                contentDescription = "Scroll by date"
                                val m = map.monthOf(map.monthStarts.getOrElse(current) { 0 })
                                stateDescription = "${monthNames[m % 12]} ${m / 12}"
                                progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), 0f..(months - 1).coerceAtLeast(1).toFloat(), steps = (months - 2).coerceAtLeast(0))
                                setProgress { value ->
                                    if (months == 0) return@setProgress false
                                    state.requestScrollToItem(map.monthStarts[value.roundToInt().coerceIn(0, months - 1)])
                                    true
                                }
                            }
                            .draggable(
                                rememberDraggableState { delta ->
                                    if (travel <= 0f) return@rememberDraggableState
                                    thumbY = (thumbY + delta).coerceIn(0f, travel)
                                    val y = (thumbY / travel * state.maxScroll(map)).toInt()
                                    val index = map.indexAt(y)
                                    state.requestScrollToItem(index, y - map.topOf(index))
                                    val at = map.monthOf(index)
                                    if (at != month) {
                                        if (at / 12 != month / 12) haptics.clockTick() else haptics.tick()
                                        month = at
                                    }
                                },
                                Orientation.Vertical,
                                startDragImmediately = true, // grab the thumb without waiting for touch slop
                                onDragStarted = {
                                    thumbY = state.progress(map) * travel
                                    month = map.monthOf(state.firstVisibleItemIndex)
                                    val max = state.maxScroll(map).toFloat()
                                    ticks = buildList {
                                        var last = Float.NEGATIVE_INFINITY
                                        for (i in map.yearStarts) {
                                            val at = (map.topOf(i) / max).coerceIn(0f, 1f)
                                            if ((at - last) * travel < tickGap) continue
                                            add(Tick("${map.monthOf(i) / 12}", at))
                                            last = at
                                        }
                                    }
                                    dragging = true
                                    CrashLog.note("fast scroll")
                                },
                                onDragStopped = { dragging = false },
                            ),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            Modifier.padding(end = Space.s2)
                                .size(width, thumbHeight)
                                .floating(CircleShape)
                                .background(c.surface, CircleShape)
                                .drawBehind {
                                    // The design's grip: three 10 x 2dp lines, 5dp apart
                                    val w = 10.dp.toPx()
                                    val h = 2.dp.toPx()
                                    val gap = 5.dp.toPx()
                                    for (k in -1..1) drawRoundRect(c.inkMuted, Offset(center.x - w / 2, center.y + k * gap - h / 2), Size(w, h), CornerRadius(h / 2))
                                },
                        )
                    }
                }
            }
        }
    }
}

/** The month (title-md) and year (meta) beside the thumb while dragging. */
@Composable
private fun Bubble(month: String, year: String) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusMd)
    Column(
        Modifier.floating(shape).background(c.surface, shape).padding(horizontal = Space.s4, vertical = Space.s2),
        horizontalAlignment = Alignment.End,
    ) {
        Text(month, style = AlbumTheme.type.titleMd, color = c.ink, maxLines = 1)
        Text(year, style = AlbumTheme.type.meta, color = c.inkMuted)
    }
}

/** How far the content can scroll by [map]'s estimate: all of it but the last screenful. */
private fun LazyGridState.maxScroll(map: ScrollMap): Int {
    val info = layoutInfo
    return (map.total - (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding)).coerceAtLeast(1)
}

/** How far down the grid is, 0..1. */
private fun LazyGridState.progress(map: ScrollMap): Float =
    ((map.topOf(firstVisibleItemIndex) + firstVisibleItemScrollOffset).toFloat() / maxScroll(map)).coerceIn(0f, 1f)
