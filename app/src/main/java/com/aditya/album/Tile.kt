package com.aditya.album

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One photo or video in a grid (the design system's MediaTile). The caller sizes it and handles taps. When [selected] the
 * image shrinks to 0.84 and rounds from the look's tile corner to radius-md, and the flash check pops in; with Remove
 * animations on both change at once. [small] (5 or more columns) leaves out the footer. [badge] is one short trait in
 * mono ("HDR", "29d"); left out, it's the photo's own ([traitBadge]) unless [small]. TalkBack reads [tileLabel].
 */
@Composable
fun MediaTile(
    m: Media,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    badge: String? = if (small) null else traitBadge(m), // tiny tiles keep only what the caller insists on
    badgeTone: BadgeTone = BadgeTone.Photo,
    checkSize: Dp = Space.check,
) {
    val spec = AlbumTheme.spec
    val c = spec.colors
    val scale by animateFloatAsState(
        if (selected) 0.84f else 1f,
        if (spec.still) snap() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )
    val corner by animateDpAsState(if (selected) Space.radiusMd else spec.tileCorner, if (spec.still) snap() else spring(), label = "tileCorner")
    // Built only when TalkBack (or a test) asks: semantics are computed lazily
    Box(modifier.semantics { contentDescription = tileLabel(m, selected) }) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(corner))
                .background(c.surfaceHigh),
        ) {
            // RAW photos' thumbnails are turned upright by Album: Android leaves some on their side (see Raw)
            val model: Any = if (isRaw(m.name)) {
                val context = LocalContext.current
                remember(m.uri, editVersion(m.uri)) { ImageRequest.Builder(context).raw(m.uri, thumb = true).build() }
            } else Thumb(m.uri)
            AsyncImage(model, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            badge?.let { Badge(it, Modifier.align(Alignment.TopEnd).padding(Space.s1), badgeTone) }
            if (!small && (m.isVideo || m.isFavorite)) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to c.scrimPhoto))
                        .padding(start = Space.s1, end = Space.s1, top = Space.s4, bottom = Space.s1),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (m.isVideo) {
                        Icon(Sym.PlayArrow, null, filled = true, size = 16.dp, tint = c.onPhoto)
                        Text(duration(m.durationMs), style = AlbumTheme.type.meta, color = c.onPhoto, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    if (m.isFavorite) Icon(Sym.Favorite, null, filled = true, size = 16.dp, tint = c.onPhoto)
                }
            }
        }
        if (selecting) SelectCheck(selected, Modifier.align(Alignment.TopStart).padding(checkSize / 3), size = checkSize)
    }
}

/** A photo's own trait for its tile: "RAW", or "Pano" for a wide panorama (tall ones could as well be long screenshots). */
fun traitBadge(m: Media): String? = when {
    m.isVideo -> null
    isRaw(m.name) -> "RAW"
    panoramaOf(m.width, m.height, isScreenshot(m.path, m.name)) == Pano.Wide -> "Pano"
    else -> null
}

/** The selection check shrinks as tiles do, so it never covers a small tile: 24dp up to 4 columns, down to 12dp at the month zoom. */
fun checkSizeFor(columns: Int): Dp = when {
    columns <= 4 -> Space.check
    columns == 5 -> 20.dp
    columns <= 7 -> 16.dp
    else -> 12.dp
}

private val spokenDay = DateTimeFormatter.ofPattern("d MMMM")
private val spokenOldDay = DateTimeFormatter.ofPattern("d MMMM yyyy")

/**
 * What TalkBack reads for a tile: kind (its badge's trait too), length, date and state, like "Video, 42 seconds,
 * 24 October, favourite, selected" or "RAW photo, 3 March".
 */
fun tileLabel(m: Media, selected: Boolean, today: LocalDate = LocalDate.now()): String {
    val date = LocalDate.ofEpochDay(m.day)
    return buildString {
        append(
            when {
                m.isVideo -> "Video, ${spokenDuration(m.durationMs)}"
                isRaw(m.name) -> "RAW photo"
                traitBadge(m) == "Pano" -> "Panorama"
                else -> "Photo"
            },
        )
        append(", ").append(date.format(if (date.year == today.year) spokenDay else spokenOldDay))
        if (m.isFavorite) append(", favourite")
        if (selected) append(", selected")
    }
}

/** A video's length as it's said: "42 seconds", "3 minutes 5 seconds", "1 hour 2 minutes". */
fun spokenDuration(ms: Long): String {
    val s = ms / 1000
    fun part(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
    return when {
        s >= 3600 -> listOfNotNull(part(s / 3600, "hour"), (s / 60 % 60).takeIf { it > 0 }?.let { part(it, "minute") }).joinToString(" ")
        s >= 60 -> listOfNotNull(part(s / 60, "minute"), (s % 60).takeIf { it > 0 }?.let { part(it, "second") }).joinToString(" ")
        else -> part(s, "second")
    }
}
