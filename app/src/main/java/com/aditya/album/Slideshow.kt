package com.aditya.album

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private val INTERVALS = listOf(2, 3, 5, 10) // seconds

/**
 * Full-screen slideshow over [items], from [startId] (or the next photo, if that's a video), looping.
 * Videos are skipped: they'd need their own length instead of a fixed interval. Drawn over the viewer, which hides
 * the system bars meanwhile. [onClose] gets the photo it ended on, so the viewer can stay there.
 */
@Composable
fun Slideshow(items: List<Media>, startId: Long, onClose: (Long) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var seconds by remember { mutableIntStateOf(prefs.getInt("slideshow_seconds", 3)) }
    var playing by rememberSaveable { mutableStateOf(true) }
    var at by rememberSaveable { mutableLongStateOf(startId) } // the photo on screen, so rotating the phone carries on from it
    var slide by remember { mutableStateOf<Slide?>(null) }
    var controls by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val close = {
        CrashLog.note("slideshow close")
        onClose(at)
    }
    BackHandler(onBack = close)

    val view = LocalView.current
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    // First photo, decoded at screen size (again after the phone rotates)
    LaunchedEffect(size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        val first = context.slideFrom(items, items.indexOfFirst { it.id == at }.coerceAtLeast(0), size) ?: return@LaunchedEffect close()
        slide = first
        at = first.media.id
    }
    // The next photo decodes while this one shows, so the crossfade never waits on a blank frame
    LaunchedEffect(slide, playing, seconds) {
        val shown = slide ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        coroutineScope {
            val next = async { context.slideFrom(items, items.indexOfFirst { it.id == shown.media.id } + 1, size) }
            delay(seconds * 1000L)
            next.await()?.let {
                slide = it
                at = it.media.id
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectTapGestures { controls = !controls } }, // also keeps touches off the viewer underneath
    ) {
        Crossfade(slide, animationSpec = tween(durationMillis = 700), label = "slide") { s ->
            if (s != null) Image(s.painter, s.media.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        AnimatedVisibility(
            controls,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    CrashLog.note(if (playing) "slideshow pause" else "slideshow play")
                    playing = !playing
                }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White) }
                INTERVALS.forEach { s ->
                    TextButton(
                        onClick = {
                            CrashLog.note("slideshow every $s s")
                            seconds = s
                            prefs.edit { putInt("slideshow_seconds", s) }
                        },
                        Modifier.semantics {
                            contentDescription = "Every $s seconds"
                            selected = s == seconds
                        },
                    ) {
                        Text("$s s", color = if (s == seconds) Color.White else Color.White.copy(alpha = 0.6f), fontWeight = if (s == seconds) FontWeight.Bold else null)
                    }
                }
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close slideshow", tint = Color.White) }
            }
        }
    }
}

private class Slide(val media: Media, val painter: Painter)

/** Decodes the first photo at or after [from] that decodes at all, wrapping around; null if none does. */
private suspend fun Context.slideFrom(items: List<Media>, from: Int, size: IntSize): Slide? {
    val loader = SingletonImageLoader.get(this)
    var i = from
    repeat(items.size) {
        val p = nextPhoto(items.size, i) { items[it].isVideo } ?: return null
        val request = ImageRequest.Builder(this).data(items[p].uri).size(size.width, size.height).build()
        (loader.execute(request) as? SuccessResult)?.let { return Slide(items[p], it.image.asPainter(this)) }
        CrashLog.note("slideshow skipped an unreadable photo")
        i = p + 1
    }
    return null
}

/** Index of the first photo at or after [from] among [count] items, wrapping around; null when they're all videos. */
fun nextPhoto(count: Int, from: Int, isVideo: (Int) -> Boolean): Int? =
    (0 until count).asSequence().map { (from + it).mod(count) }.firstOrNull { !isVideo(it) }
