package com.aditya.album

import android.text.format.Formatter
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.rememberCoroutineScope
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val timeFormat = DateTimeFormatter.ofPattern("EEE, h:mm a")

/**
 * Full-screen swipeable viewer. [items] is the list it was opened from (all photos, or one album).
 * [external] is a file another app opened: it may not be in MediaStore, so only Share and Details are offered.
 */
@Composable
fun Viewer(
    items: List<Media>,
    startId: Long,
    onClose: () -> Unit,
    external: Boolean = false,
    onTrash: ((Media) -> Unit)? = null, // the app's shared trash-with-Undo; no Delete button without it
    openFrom: Rect? = null, // on-screen grid cell the photo was opened from, to fly out of it
    closeTo: (Long) -> Rect? = { null }, // on-screen grid cell of a photo, to fly back into it (null: fade out)
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() } // last photo was deleted
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val start = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val current = items[pager.currentPage.coerceAtMost(items.lastIndex)]
    val scope = rememberCoroutineScope()

    // Opening and closing fly the photo between its grid cell and its full-screen place: 0 = in the cell, 1 = full screen.
    // The square cell crop grows into the whole photo (ContentScale.Crop on a rect that changes shape), so nothing stretches.
    val fly = remember { Animatable(if (openFrom != null) 0f else 1f) }
    var cell by remember { mutableStateOf(openFrom) }
    var closing by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Size.Zero) }
    val flying by remember { derivedStateOf { fly.value < 1f || closing } }
    LaunchedEffect(Unit) { fly.animateTo(1f, flight) }

    // Vertical swipe on an unzoomed page: down closes, up opens details. One offset (px) shared by all pages.
    var dragY by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { dragY += it }
    // 0 at rest, 1 once pulled 360 dp down. Only read inside draw and layer blocks, so dragging never recomposes.
    val pulled = { (dragY / with(density) { 360.dp.toPx() }).coerceIn(0f, 1f) }
    val close = {
        if (!closing) {
            closing = true
            cell = closeTo(current.id) // the cell may have scrolled, or this is another photo than the one opened
            scope.launch {
                fly.animateTo(0f, flight)
                onClose()
            }
        }
    }
    val release: suspend CoroutineScope.(Float) -> Unit = { velocity ->
        val swipe = with(density) { swipeOf(dragY, velocity, distance = 96.dp.toPx(), speed = 1000.dp.toPx()) }
        if (swipe != Swipe.None) CrashLog.note("swipe ${swipe.name.lowercase()}")
        if (swipe == Swipe.Close) {
            close() // flies back to its cell from where the finger left it
        } else {
            if (swipe == Swipe.Details) showInfo = true
            // spring back inside drag() so a new touch interrupts it instead of fighting it
            drag.drag { animate(dragY, 0f, velocity) { value, _ -> dragBy(value - dragY) } }
        }
    }

    BackHandler(onBack = close)
    ImmersiveBars(visible = chrome)

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { screen = it.toSize() }
            .drawBehind { drawRect(Color.Black, alpha = (1f - pulled()) * fly.value) },
    ) {
        HorizontalPager(
            pager,
            // composed from the start so the full photo loads during the flight, shown once it lands
            Modifier.fillMaxSize().graphicsLayer { alpha = if (flying) 0f else 1f },
            beyondViewportPageCount = 1,
            key = { items[it].id },
        ) { page ->
            val m = items[page]
            val settled = pager.settledPage == page
            val zoom = rememberZoomableImageState()
            // zoomed in, vertical drags pan the photo (Telephoto) instead of closing it
            val zoomed by remember(zoom) { derivedStateOf { (zoom.zoomableState.zoomFraction ?: 0f) > 0.01f } }
            Box(
                Modifier.fillMaxSize()
                    // before graphicsLayer, so drag deltas are measured on screen and not inside the moving page
                    .draggable(drag, Orientation.Vertical, enabled = settled && !zoomed, onDragStopped = release)
                    .graphicsLayer {
                        if (!settled) return@graphicsLayer
                        translationY = dragY
                        scaleX = 1f - 0.25f * pulled()
                        scaleY = scaleX
                    },
            ) {
                if (m.isVideo) {
                    VideoPage(m, active = settled, onControls = { chrome = it })
                } else {
                    // the grid's thumbnail (same Coil memory-cache key, see ThumbKeyer) shows until the full photo is ready
                    val request = remember(m.uri) { ImageRequest.Builder(context).data(m.uri).placeholderMemoryCacheKey(m.uri.toString()).build() }
                    ZoomableAsyncImage(request, m.name, Modifier.fillMaxSize(), state = zoom, onClick = { chrome = !chrome })
                }
            }
        }

        if (flying) {
            val m = current
            // Same size as the grid requested, so Coil serves the thumbnail from memory with no blank frame
            val px = cell?.let { maxOf(it.width, it.height).toInt() - 1 } ?: 512
            val thumb = remember(m.id, px) { ImageRequest.Builder(context).data(Thumb(m.uri)).size(px, px).build() }
            AsyncImage(
                thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        // read only here (layout phase), so the flight relayouts each frame without recomposing
                        val full = fitRect(m.width, m.height, screen).pulled(dragY, 1f - 0.25f * pulled())
                        val r = cell?.let { lerp(it, full, fly.value) } ?: full
                        val placeable = measurable.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
                    }
                    .graphicsLayer { alpha = if (cell == null) fly.value else 1f }, // no cell to land in: fade out instead
            )
        }

        val fade = Modifier.graphicsLayer { alpha = 1f - pulled() }
        AnimatedVisibility(chrome && !flying, Modifier.align(Alignment.TopCenter).then(fade), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                if (current.taken > 0) {
                    val time = Instant.ofEpochMilli(current.taken).atZone(ZoneId.systemDefault())
                    Column {
                        Text(time.format(dateFormat), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(time.format(timeFormat), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    // files from other apps usually have no date, so show their name instead
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        AnimatedVisibility(chrome && !flying, Modifier.align(Alignment.BottomCenter).then(fade), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).navigationBarsPadding().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Action(Icons.Outlined.Share, "Share") {
                    CrashLog.note("share 1")
                    context.startActivity(shareIntent(listOf(current)))
                }
                if (!external) {
                    Action(if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favourite") {
                        CrashLog.note("favourite ${!current.isFavorite}")
                        confirm.launch(IntentSenderRequest.Builder(favoriteRequest(context.contentResolver, listOf(current), !current.isFavorite)).build())
                    }
                    onTrash?.let { trash ->
                        Action(Icons.Outlined.Delete, "Delete") {
                            CrashLog.note("trash 1")
                            trash(current)
                        }
                    }
                }
                Action(Icons.Outlined.Info, "Details") { showInfo = true }
            }
        }
    }
    if (showInfo) InfoSheet(current) { showInfo = false }
}

private val flight = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)

/** Where a [width] x [height] photo sits when fitted, centred, inside [box]. Unknown dimensions fill the box. */
fun fitRect(width: Int, height: Int, box: Size): Rect {
    if (width <= 0 || height <= 0 || box.width <= 0f || box.height <= 0f) return Rect(Offset.Zero, box)
    val scale = minOf(box.width / width, box.height / height)
    val size = Size(width * scale, height * scale)
    return Rect(Offset((box.width - size.width) / 2, (box.height - size.height) / 2), size)
}

/** This rect as the swipe-down gesture draws it: scaled by [scale] around its centre, then moved down by [dy]. */
fun Rect.pulled(dy: Float, scale: Float): Rect =
    Rect(center.x - width * scale / 2, center.y - height * scale / 2 + dy, center.x + width * scale / 2, center.y + height * scale / 2 + dy)

/** What a released vertical swipe on an unzoomed page does. */
enum class Swipe { None, Close, Details }

/**
 * Pulling down past [distance], or flinging down faster than [speed], closes; the same upwards opens details.
 * Flinging back against the pull cancels, so you can change your mind mid-swipe. Units: px and px per second.
 */
fun swipeOf(offset: Float, velocity: Float, distance: Float, speed: Float): Swipe = when {
    offset > 0 && (velocity > speed || offset > distance && velocity > -speed) -> Swipe.Close
    offset < 0 && (velocity < -speed || offset < -distance && velocity < speed) -> Swipe.Details
    else -> Swipe.None
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

/** Plays only while it's the settled page; the player is released as soon as you swipe away. */
@Composable
private fun VideoPage(m: Media, active: Boolean, onControls: (Boolean) -> Unit) {
    if (!active) {
        AsyncImage(Thumb(m.uri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current
    // Hardware decoders occasionally choke on odd files; if that happens, retry once with the software decoder.
    var softwareOnly by remember(m.id) { mutableStateOf(false) }
    val player = remember(m.id, softwareOnly) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (softwareOnly) {
            renderers.setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.softwareOnly }
            }
        }
        ExoPlayer.Builder(context, renderers).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    CrashLog.note("video error ${error.errorCodeName} (software=$softwareOnly): ${error.cause?.message}")
                    val decoderProblem = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    if (decoderProblem && !softwareOnly) softwareOnly = true
                }
            })
            setMediaItem(MediaItem.fromUri(m.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setErrorMessageProvider { android.util.Pair(0, "Can't play this video") }
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControls(v == View.VISIBLE) })
            }
        },
        update = { it.player = player }, // picks up the software-decoder retry
        // keep the player's timeline above Album's action bar (56 dp) instead of underneath it
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 56.dp),
    )
}

/** Hides the status and navigation bars while the viewer's controls are hidden; restores them on exit. */
@Composable
private fun ImmersiveBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val dark = isSystemInDarkTheme()
    DisposableEffect(window, visible) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.isAppearanceLightStatusBars = false // white icons on the black viewer
        if (visible) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            bars.isAppearanceLightStatusBars = !dark
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(m: Media, onDismiss: () -> Unit) {
    val time = Instant.ofEpochMilli(m.taken).atZone(ZoneId.systemDefault())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            InfoRow("Name", m.name)
            if (m.taken > 0) InfoRow("Taken", "${time.format(dateFormat)}, ${time.format(timeFormat)}")
            if (m.width > 0) InfoRow("Resolution", "${m.width} × ${m.height}  (${"%.1f".format(m.width * m.height / 1_000_000f)} MP)")
            if (m.durationMs > 0) InfoRow("Length", duration(m.durationMs))
            if (m.size > 0) InfoRow("Size", Formatter.formatShortFileSize(LocalContext.current, m.size)) // KB / MB / GB as fits
            val folder = m.path.ifEmpty { m.bucketName }
            if (folder.isNotEmpty()) InfoRow("Folder", folder) // empty for files opened from other apps
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
