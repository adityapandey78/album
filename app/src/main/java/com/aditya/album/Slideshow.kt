package com.aditya.album

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale

/** How long each photo shows. */
private const val SLIDE_MS = 3_200f

/** Past this many photos the bars would be slivers, so a single bar times the current photo instead. */
private const val MAX_BARS = 30

/**
 * Plays [items] as a story, from [startId] (or the next photo, if that's a video) to the end: one photo at a time,
 * cropped to fill the screen with a slow zoom, progress bars at the top, and at the bottom [title] on the first photo
 * (a memory's name) or else the photo's day. [eyebrow] sits beside the logo ("On this day · 2025"). Tap the left third
 * to go back, anywhere else to go on; press and hold to pause. Videos are skipped: they'd need their own length instead
 * of a fixed time. Drawn over everything with the system bars hidden. [onClose] gets the photo it ended on: after the
 * last photo, on close, or on back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Slideshow(items: List<Media>, startId: Long, onClose: (Long) -> Unit, title: String? = null, eyebrow: String? = null) {
    val slides = remember(items, startId) { storySlides(items, items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { it.isVideo } }
    if (slides.isEmpty()) {
        LaunchedEffect(Unit) { onClose(startId) }
        return
    }
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val still = AlbumTheme.spec.still
    val placeOf = rememberPlaces()
    var at by rememberSaveable { mutableLongStateOf(slides.first().id) } // by id, so rotating the phone carries on from it
    val index = slides.indexOfFirst { it.id == at }.coerceAtLeast(0) // the list can change under it (a photo deleted)
    var shown by remember { mutableStateOf<Slide?>(null) } // decoded; stays up until the next one is ready
    var held by remember { mutableStateOf(false) }
    // The current photo's time, 0 to 1. Changes every frame, so it's only read while drawing (bars, zoom), never composing.
    val progress = remember { mutableFloatStateOf(0f) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val close = {
        CrashLog.note("slideshow close")
        onClose(slides[index].id)
    }
    val go: (Int) -> Unit = { to ->
        progress.floatValue = 0f
        if (to >= slides.size) close() else at = slides[to.coerceAtLeast(0)].id // back from the first photo restarts it
    }
    val step by rememberUpdatedState { delta: Int -> go(index + delta) } // for the gesture detector, which outlives compositions
    BackHandler(onBack = close)
    ImmersiveBars(visible = false)
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Decoded at screen size, cropping (FILL), then the next photo is decoded into Coil's memory cache meanwhile, so the
    // cut to it never waits on a blank frame. Again after the phone rotates.
    LaunchedEffect(at, screen) {
        if (screen == IntSize.Zero) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val image = (loader.execute(context.slideRequest(slides[index], screen)) as? SuccessResult)?.image
        if (image == null) {
            CrashLog.note("slideshow skipped an unreadable photo")
            go(index + 1)
            return@LaunchedEffect
        }
        shown = Slide(slides[index], image.asPainter(context))
        slides.getOrNull(index + 1)?.let { loader.enqueue(context.slideRequest(it, screen)) }
    }
    // The photo's time runs once it's on screen, and stops while held. On the frame clock rather than an animation:
    // Remove animations stops animations, but progress is information and keeps moving (motion.md).
    LaunchedEffect(shown, held) {
        if (shown?.media?.id != at || held) return@LaunchedEffect
        val from = progress.floatValue
        val start = withFrameMillis { it }
        while (progress.floatValue < 1f) {
            progress.floatValue = from + withFrameMillis { it - start } / SLIDE_MS
        }
        step(1)
    }

    Box(
        Modifier.fillMaxSize()
            .background(c.void)
            .onSizeChanged { screen = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    held = true
                    val up = waitForUpOrCancellation()
                    held = false
                    // A tap steps; a hold only paused
                    if (up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        up.consume()
                        step(if (down.position.x < size.width / 3f) -1 else 1)
                    }
                }
            },
    ) {
        shown?.let { s ->
            Image(
                s.painter,
                s.media.name,
                Modifier.fillMaxSize().graphicsLayer {
                    // A slow drift from 102% to 112% over the photo's time; none with Remove animations
                    val zoom = if (still) 1f else 1.02f + 0.10f * progress.floatValue.coerceAtMost(1f)
                    scaleX = zoom
                    scaleY = zoom
                },
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to c.scrimPhoto, 0.28f to Color.Transparent, 0.5f to Color.Transparent, 1f to c.scrimPhoto),
            ),
        )

        // The system bars are hidden, but the text keeps clear of where they'd be (and of the camera cutout)
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = Space.s2)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
                val bars = if (slides.size <= MAX_BARS) slides.size else 1
                repeat(bars) { k ->
                    Box(
                        Modifier.weight(1f).height(progressLine).clip(CircleShape).background(c.onPhoto.copy(alpha = 0.35f)).drawBehind {
                            val filled = when {
                                bars == 1 || k == index -> progress.floatValue.coerceAtMost(1f)
                                k < index -> 1f
                                else -> 0f
                            }
                            drawRect(c.onPhoto, size = Size(size.width * filled, size.height))
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s2, top = Space.s1),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The mark follows the theme; over a photo it's always the dark-ground version
                AlbumTheme(AlbumTheme.spec.appearance.copy(theme = ThemeMode.Dark)) { LogoMark(size = Space.iconSm) }
                if (eyebrow != null) Text(eyebrow, Modifier.weight(1f), style = type.meta, color = c.onPhoto) else Spacer(Modifier.weight(1f))
                AlbumIconButton(Sym.Close, "Close", close, variant = IconButtonVariant.Photo)
            }
        }

        shown?.media?.let { m ->
            Column(
                Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(start = Space.edgeSafe, end = Space.edgeSafe, bottom = Space.s12),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                if (title != null && m.id == slides.first().id) {
                    Text(title, style = type.display, color = c.onPhoto)
                } else {
                    Text(dayTitle(m.day), style = type.titleLg, color = c.onPhoto)
                }
                placeOf[m.id]?.let { Text(placeLabel(it), style = type.caption, color = c.onPhoto) }
            }
        }
    }
}

private class Slide(val media: Media, val painter: Painter)

private fun Context.slideRequest(m: Media, size: IntSize) =
    ImageRequest.Builder(this).data(m.uri).size(size.width, size.height).scale(Scale.FILL).build()

/** What a story plays: the first photo at or after [start] (wrapping around), then every photo after it. */
fun <T> storySlides(items: List<T>, start: Int, isVideo: (T) -> Boolean): List<T> {
    val first = nextPhoto(items.size, start) { isVideo(items[it]) } ?: return emptyList()
    return items.subList(first, items.size).filterNot(isVideo)
}

/** Index of the first photo at or after [from] among [count] items, wrapping around; null when they're all videos. */
fun nextPhoto(count: Int, from: Int, isVideo: (Int) -> Boolean): Int? =
    (0 until count).asSequence().map { (from + it).mod(count) }.firstOrNull { !isVideo(it) }
