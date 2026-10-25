package com.aditya.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val thumbHeight = 48.dp

/**
 * Draggable thumb on the right edge for jumping through a long timeline. It shows while the grid scrolls, fades out
 * 1.5 s after it stops, and only appears when there are more than about three screens of photos.
 * Dragging jumps proportionally through the whole grid, with a bubble showing [labelAt] for the grid position at the top.
 */
@Composable
fun FastScroller(state: LazyGridState, columns: Int, contentPadding: PaddingValues, modifier: Modifier = Modifier, labelAt: (Int) -> String) {
    val haptics = LocalHapticFeedback.current
    val thumbPx = with(LocalDensity.current) { thumbHeight.toPx() }
    // derivedStateOf: recompose when the answer changes, not on every scrolled frame
    val long by remember(state) { derivedStateOf { state.layoutInfo.run { totalItemsCount > visibleItemsInfo.size * 3 } } }
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
    var track by remember { mutableFloatStateOf(0f) } // how far the thumb can travel, px
    var thumbY by remember { mutableFloatStateOf(0f) } // thumb top while dragging, px
    var label by remember { mutableStateOf("") }

    Box(
        modifier.fillMaxHeight()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
            .onSizeChanged { track = it.height - thumbPx },
    ) {
        AnimatedVisibility(shown && long, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.offset { IntOffset(0, (if (dragging) thumbY else state.progress(columns) * track).roundToInt()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dragging) {
                    Text(
                        label,
                        Modifier.padding(end = 8.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Box(
                    Modifier
                        .testTag("fast_scroller")
                        .draggable(
                            rememberDraggableState { delta ->
                                if (track <= 0f) return@rememberDraggableState
                                thumbY = (thumbY + delta).coerceIn(0f, track)
                                val target = (thumbY / track * state.layoutInfo.scrollableItems).toInt()
                                state.requestScrollToItem(target)
                                val at = labelAt(target)
                                if (at != label) {
                                    label = at
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            },
                            Orientation.Vertical,
                            startDragImmediately = true, // grab the thumb without waiting for touch slop
                            onDragStarted = {
                                thumbY = state.progress(columns) * track
                                label = labelAt(state.firstVisibleItemIndex)
                                dragging = true
                                CrashLog.note("fast scroll")
                            },
                            onDragStopped = { dragging = false },
                        )
                        .padding(start = 12.dp) // 48 dp touch target around the narrower thumb
                        .size(36.dp, thumbHeight)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

/** Items that can scroll past the top: everything except roughly the last screenful. */
private val LazyGridLayoutInfo.scrollableItems get() = (totalItemsCount - visibleItemsInfo.size).coerceAtLeast(1)

/** How far down the grid is, 0..1, by the first visible item, moving smoothly within its row. */
private fun LazyGridState.progress(columns: Int): Float {
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex } ?: return 0f
    val perLine = if (first.key is Long) columns else 1 // a header line holds one item, a photo row holds [columns]
    val within = firstVisibleItemScrollOffset.toFloat() / first.size.height.coerceAtLeast(1) * perLine
    return ((firstVisibleItemIndex + within) / info.scrollableItems).coerceIn(0f, 1f)
}
