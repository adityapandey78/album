package com.aditya.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Column counts you can pinch between. 7 switches the headers from days to months. */
private val columnSteps = listOf(2, 3, 4, 5, 7)
const val DEFAULT_COLUMNS = 4

class Section<T>(val key: Long, val label: String, val items: List<T>)

/** Groups newest-first [media] into day sections (or month sections), labelled like the OPPO gallery. */
fun <T> sections(media: List<T>, byMonth: Boolean, today: Long = LocalDate.now().toEpochDay(), dayOf: (T) -> Long): List<Section<T>> {
    val thisYear = LocalDate.ofEpochDay(today).year
    val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM")
    val oldDayFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
    val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")
    fun keyOf(m: T) = dayOf(m).let { day -> if (byMonth) LocalDate.ofEpochDay(day).let { it.year * 12L + it.monthValue } else day }
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

/**
 * The photo grid used by Photos and by every album.
 * [columns] and [state] are owned by the caller so scroll position and zoom level survive opening a photo.
 */
@Composable
fun MediaGrid(
    media: List<Media>,
    columns: MutableIntState,
    state: LazyGridState,
    selected: Set<Long>,
    onOpen: (Media) -> Unit,
    onToggle: (Media) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val spec = LocalLook.current
    val byMonth = columns.intValue >= 7
    val sections = remember(media, byMonth) { sections(media, byMonth) { it.day } }
    val selecting = selected.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.intValue),
        state = state,
        modifier = modifier.fillMaxSize().pinchToZoom(columns) { zoomIn ->
            val i = columnSteps.indexOf(columns.intValue).coerceAtLeast(0)
            columns.intValue = columnSteps[(if (zoomIn) i - 1 else i + 1).coerceIn(0, columnSteps.lastIndex)]
        },
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(spec.gridGap),
        verticalArrangement = Arrangement.spacedBy(spec.gridGap),
    ) {
        sections.forEach { section ->
            item(key = "h${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Text(
                    section.label,
                    Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(section.items, key = { it.id }, contentType = { "cell" }) { m ->
                Cell(m, small = columns.intValue >= 5, isSelected = m.id in selected, selecting, onOpen, onToggle)
            }
        }
    }
}

private val badgeShadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(m: Media, small: Boolean, isSelected: Boolean, selecting: Boolean, onOpen: (Media) -> Unit, onToggle: (Media) -> Unit) {
    val spec = LocalLook.current
    Box(
        Modifier.aspectRatio(1f)
            .clip(RoundedCornerShape(spec.cellCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = { if (selecting) onToggle(m) else onOpen(m) }, onLongClick = { onToggle(m) }),
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
            val check = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
            if (isSelected) {
                Icon(Icons.Filled.CheckCircle, "Selected", check.background(Color.White, CircleShape), tint = MaterialTheme.colorScheme.primary)
            } else {
                Box(check.border(1.5.dp, Color.White, CircleShape))
            }
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
