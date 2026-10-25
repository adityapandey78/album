package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The dock floats this far above the navigation bar (the design's 28px). */
private val dockGap = Space.s6 + Space.s1

/** Where snackbars sit over the viewer, above the navigation bar: just clear of the dock. For the shell's Undo too. */
val viewerSnackbarGap = dockGap + Space.bar + Space.s3

/** Progress lines (the video's, a story's bars) are this thick, as in the design. */
internal val progressLine = 3.dp

/**
 * Full-screen swipeable viewer. [items] is the list it was opened from (all photos, or one album).
 * [external] is a file another app opened: it may not be in MediaStore, so only Share, Edit and Details are offered.
 */
@Composable
fun Viewer(
    items: List<Media>,
    startId: Long,
    onClose: () -> Unit,
    external: Boolean = false,
    onTrash: ((Media) -> Unit)? = null, // the app's shared trash-with-Undo; no Move to trash button without it
    openFrom: Rect? = null, // on-screen grid cell the photo was opened from, to fly out of it
    closeTo: (Long) -> Rect? = { null }, // on-screen grid cell of a photo, to fly back into it (null: fade out)
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() } // last photo was deleted
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val still = AlbumTheme.spec.still
    val start = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val current = items[pager.currentPage.coerceAtMost(items.lastIndex)]
    val scope = rememberCoroutineScope()
    // The viewer's own messages. Undo after Move to trash is the caller's (onTrash), in the shell's snackbar.
    val snackbar = remember { SnackbarHostState() }
    val say: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val rotate = rememberRotate(onMessage = say)
    val edit = rememberEdit(onMessage = say)
    val clock = rememberClock()
    val placeOf = if (external) emptyMap() else rememberPlaces()
    val hasPhotos = remember(items) { items.any { !it.isVideo } } // a slideshow needs at least one
    var slideshowFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    // Motion photo: its video plays in place over the still, from the Live badge or a long-press
    val motion = rememberMotionVideo(current)
    var playMotion by remember(current.id) { mutableStateOf(false) }
    var motionMuted by remember { mutableStateOf(true) }
    var videoMuted by remember { mutableStateOf(false) }

    // Opening and closing fly the photo between its grid cell and its full-screen place: 0 = in the cell, 1 = full screen.
    // The square cell crop grows into the whole photo (ContentScale.Crop on a rect that changes shape), so nothing stretches.
    // With Remove animations the photo appears in place instead.
    val fly = remember { Animatable(if (openFrom != null && !still) 0f else 1f) }
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
        if (still) {
            onClose() // disappears from where the finger left it, without flying back
        } else if (!closing) {
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
    ImmersiveBars(visible = chrome && slideshowFrom == null)

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { screen = it.toSize() }
            .drawBehind { drawRect(c.void, alpha = (1f - pulled()) * fly.value) },
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
            // Rotate edits the file in place under the same uri, and Telephoto keeps its decoded tiles and Exif per uri:
            // a fresh state per edit makes it read the new orientation instead of showing the old one
            val zoom = key(editVersion(m.uri)) { rememberZoomableImageState() }
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
                    VideoPage(
                        m,
                        active = settled && slideshowFrom == null,
                        chrome = chrome,
                        muted = videoMuted,
                        onTap = { chrome = !chrome },
                        onHideChrome = { chrome = false },
                    )
                } else {
                    // the grid's thumbnail (same Coil memory-cache key, see ThumbKeyer) shows until the full photo is ready
                    val request = remember(m.uri, editVersion(m.uri)) {
                        ImageRequest.Builder(context).data(m.uri).placeholderMemoryCacheKey("${m.uri}#${editVersion(m.uri)}").build()
                    }
                    val video = motion?.takeIf { m.id == current.id }
                    key(editVersion(m.uri)) { // rebuilt after an edit, like reopening the photo, so nothing stale is drawn
                    ZoomableAsyncImage(
                        request,
                        m.name,
                        Modifier.fillMaxSize(),
                        state = zoom,
                        onClick = { chrome = !chrome },
                        onLongClick = video?.let {
                            { _: Offset ->
                                CrashLog.note("motion play (hold)")
                                haptics.longPress()
                                playMotion = true
                            }
                        },
                    )
                    }
                    if (video != null && playMotion) MotionPlayer(m.uri, video, motionMuted, onEnd = { playMotion = false })
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
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(c.scrimPhoto, Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = Space.s2, end = Space.s2, top = Space.s1, bottom = Space.s8),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumIconButton(Sym.ArrowBack, "Back", close, variant = IconButtonVariant.Photo)
                Column(Modifier.weight(1f)) {
                    if (current.taken > 0) {
                        Text(dayTitle(current.day), style = type.label, color = c.onPhoto)
                        val place = placeOf[current.id]?.city
                        Text(listOfNotNull(clock(current.taken), place).joinToString(" · "), style = type.meta, color = c.onPhoto)
                    } else {
                        // files from other apps usually have no date, so show their name instead
                        Text(current.name, style = type.label, color = c.onPhoto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (motion != null) {
                    LiveBadge(onPlay = {
                        CrashLog.note("motion play")
                        playMotion = true
                    })
                }
                if (current.isVideo || playMotion) {
                    val muted = if (current.isVideo) videoMuted else motionMuted
                    AlbumIconButton(
                        if (muted) Sym.VolumeOff else Sym.VolumeUp,
                        if (muted) "Turn sound on" else "Turn sound off",
                        onClick = {
                            CrashLog.note("${if (current.isVideo) "video" else "motion"} sound ${if (muted) "on" else "off"}")
                            if (current.isVideo) videoMuted = !muted else motionMuted = !muted
                        },
                        variant = IconButtonVariant.Photo,
                    )
                }
                if (!external && hasPhotos) {
                    AlbumIconButton(Sym.MoreVert, "More", { showMore = true }, variant = IconButtonVariant.Photo)
                }
            }
        }

        AnimatedVisibility(chrome && !flying, Modifier.align(Alignment.BottomCenter).then(fade), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.navigationBarsPadding().padding(bottom = dockGap).height(Space.bar).floating().padding(horizontal = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumIconButton(Sym.Share, "Share", {
                    CrashLog.note("share 1")
                    context.startActivity(shareIntent(listOf(current)))
                })
                if (!external) {
                    AlbumIconButton(
                        Sym.Favorite,
                        "Favourite",
                        {
                            CrashLog.note("favourite ${!current.isFavorite}")
                            confirm.launch(IntentSenderRequest.Builder(favoriteRequest(context.contentResolver, listOf(current), !current.isFavorite)).build())
                        },
                        Modifier.semantics { stateDescription = if (current.isFavorite) "On" else "Off" },
                        active = current.isFavorite,
                    )
                }
                AlbumIconButton(Sym.Tune, "Edit", { edit(current) })
                AlbumIconButton(Sym.Info, "Details", {
                    CrashLog.note("details")
                    showInfo = true
                })
                if (!external) {
                    onTrash?.let { trash ->
                        AlbumIconButton(Sym.Delete, "Move to trash", {
                            CrashLog.note("trash 1")
                            trash(current)
                        })
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = viewerSnackbarGap)) { AlbumSnackbar(it) }

        slideshowFrom?.let { id ->
            Slideshow(items, id, onClose = { endedOn ->
                slideshowFrom = null
                val page = items.indexOfFirst { it.id == endedOn }
                if (page >= 0) scope.launch { pager.scrollToPage(page) }
            })
        }
    }
    if (showInfo) InfoSheet(current, placeOf[current.id]) { showInfo = false }
    if (showMore) {
        val title = if (current.taken > 0) "${dayTitle(current.day)} · ${clock(current.taken)}" else current.name
        MoreSheet(
            current,
            title,
            canSlideshow = hasPhotos,
            onSlideshow = {
                CrashLog.note("slideshow")
                slideshowFrom = current.id
            },
            onRotate = { clockwise -> rotate(current, clockwise) },
            onCopyText = {
                CrashLog.note("copy text")
                scope.launch {
                    val text = withContext(Dispatchers.IO) { runCatching { SmartIndex.textOf(current.id) }.getOrNull() }
                    if (text.isNullOrBlank()) say("No text found in this photo")
                    else {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Text from photo", text))
                        say("Copied text from this photo")
                    }
                }
            },
            onDismiss = { showMore = false },
        )
    }
}

/** The viewer's More sheet: actions used less often than the dock's. Rotate and Use as are for photos only. */
@Composable
private fun MoreSheet(
    m: Media,
    title: String,
    canSlideshow: Boolean,
    onSlideshow: () -> Unit,
    onRotate: (clockwise: Boolean) -> Unit,
    onCopyText: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Sheet(onDismiss, title = title) {
        // Each row closes the sheet, then acts
        fun closing(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        if (canSlideshow) MenuRow(Sym.Slideshow, "Slideshow", closing(onSlideshow))
        if (!m.isVideo) {
            MenuRow(Sym.RotateRight, "Rotate right", closing { onRotate(true) }, sub = "Lossless, keeps quality")
            MenuRow(Sym.RotateLeft, "Rotate left", closing { onRotate(false) })
            MenuRow(Sym.Wallpaper, "Use as", closing {
                CrashLog.note("use as")
                // Wallpaper, contact photo and the like. Some of those apps read the type from the "mimeType" extra.
                val attach = Intent(Intent.ACTION_ATTACH_DATA)
                    .setDataAndType(m.uri, "image/*")
                    .putExtra("mimeType", "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startSafely(Intent.createChooser(attach, "Use as"))
            }, sub = "Wallpaper, contact photo")
            // Words the smart index read from the photo (screenshots, bills, tickets); never leaves the phone
            MenuRow(Sym.ContentCopy, "Copy text from photo", closing(onCopyText))
        }
    }
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

// ---------- Video ----------

/** The player's own controls used to hide this long into playback; the viewer's chrome does the same. */
private const val CONTROLS_MS = 5_000L

/**
 * Plays only while it's the settled page; the player is released as soon as you swipe away. Tapping toggles the chrome;
 * with it come a big play/pause in the middle and (always) a progress line in flash along the video's bottom edge,
 * which seeks when dragged or tapped.
 */
@Composable
private fun VideoPage(m: Media, active: Boolean, chrome: Boolean, muted: Boolean, onTap: () -> Unit, onHideChrome: () -> Unit) {
    if (!active) {
        AsyncImage(Thumb(m.uri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current
    val c = AlbumTheme.colors
    // Hardware decoders occasionally choke on odd files; if that happens, retry once with the software decoder.
    var softwareOnly by remember(m.id) { mutableStateOf(false) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var playing by remember(m.id) { mutableStateOf(true) } // wants to play: paused and finished are both false
    // As shown: the player turns sideways-stored phone videos upright, which MediaStore's width and height may not say
    var shape by remember(m.id) { mutableStateOf(m.width to m.height) }
    val player = remember(m.id, softwareOnly) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (softwareOnly) {
            renderers.setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.softwareOnly }
            }
        }
        ExoPlayer.Builder(context, renderers).build().apply {
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0) shape = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt() to videoSize.height
                }

                override fun onPlayerError(error: PlaybackException) {
                    CrashLog.note("video error ${error.errorCodeName} (software=$softwareOnly): ${error.cause?.message}")
                    val decoderProblem = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    if (decoderProblem && !softwareOnly) softwareOnly = true else failed = true
                }
            })
            setMediaItem(MediaItem.fromUri(m.uri))
            prepare()
            playWhenReady = true
        }
    }
    SideEffect { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(chrome, playing) {
        if (chrome && playing) {
            delay(CONTROLS_MS)
            onHideChrome()
        }
    }
    // 0 to 1. Updated every frame while playing, but read only while drawing the line, so playback never recomposes.
    val progress = remember(m.id) { mutableFloatStateOf(0f) }
    val length = { player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: m.durationMs }
    LaunchedEffect(player, playing) {
        while (true) {
            val total = length()
            if (total > 0) progress.floatValue = (player.currentPosition.toFloat() / total).coerceIn(0f, 1f)
            if (!playing) break
            withFrameMillis { }
        }
    }
    val seek = { fraction: Float ->
        val total = length()
        if (total > 0) {
            player.seekTo((fraction * total).toLong())
            progress.floatValue = fraction
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false // Album draws its own controls
                    this.player = player
                }
            },
            update = { it.player = player }, // picks up the software-decoder retry
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Above the player view, which would otherwise take the touches
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
        if (failed) {
            Text("Can't play this video", Modifier.align(Alignment.Center), style = AlbumTheme.type.body, color = c.onPhoto)
            return@BoxWithConstraints
        }
        AnimatedVisibility(chrome || !playing, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            AlbumIconButton(
                if (playing) Sym.Pause else Sym.PlayArrow,
                if (playing) "Pause" else "Play",
                onClick = {
                    CrashLog.note(if (playing) "video pause" else "video play")
                    if (playing) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                },
                Modifier.size(Space.bar), // the design's 64dp: the one big control on a video
                variant = IconButtonVariant.Photo,
            )
        }

        // The line runs along the bottom of the picture, but never lower than just above the navigation bar, where a
        // sideways drag would switch apps instead of seeking. Its touch strip is a full target tall.
        val density = LocalDensity.current
        val frame = with(density) { fitRect(shape.first, shape.second, Size(maxWidth.toPx(), maxHeight.toPx())) }
        val lowest = with(density) { maxHeight.toPx() - WindowInsets.navigationBars.getBottom(this) - Space.s6.toPx() }
        val lineY = minOf(frame.bottom, lowest)
        Box(
            Modifier
                .offset { IntOffset(frame.left.roundToInt(), (lineY - Space.target.toPx() / 2).roundToInt()) }
                .size(with(density) { frame.width.toDp() }, Space.target)
                .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume() // so the pager doesn't turn the page meanwhile
                        seek((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .drawBehind {
                    val h = progressLine.toPx()
                    drawRect(c.flash, Offset(0f, size.height / 2 - h), Size(size.width * progress.floatValue, h))
                },
        )
    }
}

/** Hides the status and navigation bars while not [visible], with light icons over the black viewer; restores them after. */
@Composable
internal fun ImmersiveBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val dark = AlbumTheme.colors.dark
    DisposableEffect(window, visible) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        if (visible) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
}

// ---------- Edit ----------

private const val GOOGLE_PHOTOS = "com.google.android.apps.photos"

/** ACTION_EDIT for [uri], for the app [pkg] (null: any editor). [write] lets the editor save over the original. */
fun editIntent(uri: Uri, mime: String, write: Boolean, pkg: String?): Intent =
    Intent(Intent.ACTION_EDIT).setDataAndType(uri, mime).setPackage(pkg).addFlags(
        if (write) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )

/**
 * Starts [uri] in Google Photos' editor, else in any editor (Android asks which when there are several); false when no
 * app on the phone can edit it. Write access comes first so an edit can replace the original; where Album can't hand
 * that out (another app's file, a file from another app's provider) the editor gets read access and saves a copy.
 */
private fun openEditor(uri: Uri, mime: String, start: (Intent) -> Unit): Boolean {
    for (pkg in listOf(GOOGLE_PHOTOS, null)) {
        for (write in listOf(true, false)) {
            try {
                // Not Intent.createChooser for the fallback: a chooser always opens, even empty, so it could never tell
                // that no app can edit this. A plain implicit intent gets Android's own picker or ActivityNotFoundException.
                start(editIntent(uri, mime, write, pkg))
                return true
            } catch (e: ActivityNotFoundException) {
                break // not installed, or can't edit this type: try the next
            } catch (e: SecurityException) {
                CrashLog.note("edit: can't grant write access ($e)") // again, read-only
            }
        }
    }
    return false
}

/**
 * Returns a function that opens a photo or video in an editor ([openEditor]), saying so in [onMessage] when nothing on
 * the phone can edit it. If the editor saved over the file, every cached copy reloads once the person comes back.
 */
@Composable
private fun rememberEdit(onMessage: (String) -> Unit): (Media) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable: Android may recreate the viewer while the editor is open
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var before by rememberSaveable { mutableStateOf<Long?>(null) }
    val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = editing?.let(Uri::parse)
        val generation = before
        editing = null
        // Only MediaStore files have a generation; anything else is never written back by an editor Album opens
        if (uri == null || generation == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            // MediaStore rescans a file once the editor closes it; most editors save a copy instead, so this times out
            val changed = withContext(Dispatchers.IO) {
                withTimeoutOrNull(3_000) { Gallery.changes(resolver).first { runCatching { generationOf(resolver, uri) }.getOrNull() != generation } }
            } != null
            if (changed) {
                CrashLog.note("edit saved over the original")
                reloadEdited(context, uri)
            }
        }
    }
    return { m ->
        CrashLog.note("edit")
        scope.launch {
            val resolver = context.contentResolver
            val (mime, generation) = withContext(Dispatchers.IO) {
                runCatching { resolver.getType(m.uri) }.getOrNull() to runCatching { generationOf(resolver, m.uri) }.getOrNull()
            }
            editing = m.uri.toString()
            before = generation
            if (!openEditor(m.uri, mime ?: if (m.isVideo) "video/*" else "image/*", editor::launch)) {
                editing = null
                onMessage("No app on this phone can edit this")
            }
        }
    }
}

// ---------- Details ----------

@Composable
private fun InfoSheet(m: Media, place: Place?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val clock = rememberClock()
    // Camera, exposure, location and format come from the file, read off the main thread; the rest is already in [m]
    val file by produceState<FileDetails?>(null, m.uri, editVersion(m.uri)) {
        value = withContext(Dispatchers.IO) { readDetails(context, m) }
    }
    Sheet(onDismiss) {
        Column(Modifier.padding(bottom = Space.s3)) {
            if (m.taken > 0) {
                val taken = Instant.ofEpochMilli(m.taken).atZone(ZoneId.systemDefault())
                Text(taken.format(longDay), style = type.titleLg, color = c.ink)
                Text("${clock(m.taken)} · ${m.name}", style = type.meta, color = c.inkMuted)
            } else {
                Text(m.name, style = type.titleLg, color = c.ink) // files from other apps usually have no date
            }
        }
        InfoRow(if (m.isVideo) Sym.Videocam else Sym.PhotoCamera) {
            // blank until the file is read, so the row doesn't jump from "No camera details" to the camera
            val camera = file?.let { it.camera ?: if (m.isVideo) "Video" else "No camera details" }.orEmpty()
            Text(camera, style = type.label, color = c.ink)
            file?.exif?.let { Text(it, style = type.metaLg, color = c.ink) }
            if (m.width > 0) {
                val pixels = "${m.width} × ${m.height} · ${"%.1f".format(m.width * m.height / 1_000_000f)} MP"
                Text(if (m.durationMs > 0) "$pixels · ${duration(m.durationMs)}" else pixels, style = type.meta, color = c.inkMuted)
            } else if (m.durationMs > 0) {
                Text(duration(m.durationMs), style = type.meta, color = c.inkMuted)
            }
        }
        val latLng = file?.latLng
        if (place != null || latLng != null) {
            InfoRow(Sym.LocationOn) {
                val coords = latLng?.let { coordinates(it[0], it[1]) }
                Text(place?.let(::placeLabel) ?: coords.orEmpty(), style = type.label, color = c.ink)
                if (place != null && coords != null) Text(coords, style = type.meta, color = c.inkMuted)
            }
        }
        InfoRow(Sym.Folder) {
            // The real path, in full. Files from other apps have none, only a name.
            Text(m.path + m.name, style = type.meta, color = c.ink)
            val size = m.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) } // KB / MB / GB as fits
            val caption = listOfNotNull(size, file?.format).joinToString(" · ")
            if (caption.isNotEmpty()) Text(caption, style = type.caption, color = c.inkMuted)
        }
    }
}

/** A details row under a hairline: an icon in ink-muted, then its lines. */
@Composable
private fun InfoRow(icon: Sym, content: @Composable ColumnScope.() -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
            .padding(vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Icon(icon, null, tint = c.inkMuted)
        Column(Modifier.weight(1f), content = content)
    }
}

/** What the details sheet reads from the file itself. [latLng] needs Album's location permission. */
private class FileDetails(val camera: String?, val exif: String?, val latLng: DoubleArray?, val format: String?)

/** Blocking: call off the main thread. Never fails; whatever can't be read is left out. */
private fun readDetails(context: Context, m: Media): FileDetails {
    val resolver = context.contentResolver
    val format = fileFormat(m.name, runCatching { resolver.getType(m.uri) }.getOrNull())
    // ponytail: videos keep camera and location in MP4 boxes ExifInterface doesn't read; MediaMetadataRetriever could
    if (m.isVideo) return FileDetails(null, null, null, format)
    // Android hides GPS unless Album holds ACCESS_MEDIA_LOCATION and asks for the original file. Read-only either way.
    val original = m.uri.authority == MediaStore.AUTHORITY &&
        context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    val exif = runCatching {
        resolver.openFileDescriptor(if (original) MediaStore.setRequireOriginal(m.uri) else m.uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    }.onFailure { CrashLog.note("details exif failed: $it") }.getOrNull() ?: return FileDetails(null, null, null, format)
    val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).toDouble()
    return FileDetails(
        camera = cameraName(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL)),
        exif = exifLine(
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0),
            exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0),
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0),
            // phones' real focal lengths (4.7 mm) say little; the 35 mm equivalent (24 mm) is what people compare
            focal = focal35.takeIf { it > 0 } ?: exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0),
        ),
        latLng = exif.latLong?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }, // some cameras write 0,0 when they had no fix
        format = format,
    )
}

// ---------- Words and numbers ----------

private val shortDay = DateTimeFormatter.ofPattern("EEE, d MMM")
private val shortDayYear = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val longDay = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy")

/** How the viewer and stories name a day: "Today", "Yesterday", "Sat, 24 Oct", or "Sat, 24 Oct 2025" from another year. */
fun dayTitle(day: Long, today: Long = LocalDate.now().toEpochDay()): String {
    val date = LocalDate.ofEpochDay(day)
    return when {
        day == today -> "Today"
        day == today - 1 -> "Yesterday"
        date.year == LocalDate.ofEpochDay(today).year -> date.format(shortDay)
        else -> date.format(shortDayYear)
    }
}

/** Formats a time of day as the phone does: "4:05 pm" or "16:05", following its 24-hour setting. */
@Composable
private fun rememberClock(): (Long) -> String {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    return { millis -> format.format(Date(millis)) }
}

/** The camera as people name it ("motorola edge 70 fusion"): the maker only when the model doesn't already start with it. */
fun cameraName(make: String?, model: String?): String? {
    val maker = make?.trim().orEmpty()
    val name = model?.trim().orEmpty()
    return when {
        name.isEmpty() -> maker.ifEmpty { null }
        maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
        else -> "$maker $name"
    }
}

/** The exposure line of the details sheet, "ƒ/1.8  1/120 s  ISO 200  24 mm"; null when the camera wrote none of it. 0 = missing. */
fun exifLine(fNumber: Double, exposure: Double, iso: Int, focal: Double): String? = listOfNotNull(
    fNumber.takeIf { it > 0 }?.let { "ƒ/${plain(it)}" },
    exposure.takeIf { it > 0 }?.let { if (it < 1) "1/${(1 / it).roundToInt()} s" else "${plain(it)} s" },
    iso.takeIf { it > 0 }?.let { "ISO $it" },
    focal.takeIf { it > 0 }?.let { "${plain(it)} mm" },
).joinToString("  ").ifEmpty { null }

/** One decimal at most, none when it's a whole number: 1.8, 2, 4.7. */
private fun plain(x: Double) = "%.1f".format(Locale.ROOT, x).removeSuffix(".0")

/** "12.9716° N, 77.5946° E" */
fun coordinates(lat: Double, lng: Double): String =
    "%.4f° %s, %.4f° %s".format(Locale.ROOT, abs(lat), if (lat < 0) "S" else "N", abs(lng), if (lng < 0) "W" else "E")

/** The file type as people know it ("JPEG", "HEIC", "MP4"): from the file name, else from its MIME type. */
fun fileFormat(name: String, mime: String?): String? {
    val extension = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return when {
        extension == "JPG" -> "JPEG"
        extension.isNotEmpty() -> extension
        else -> mime?.substringAfter('/', "")?.uppercase(Locale.ROOT)?.ifEmpty { null }
    }
}

/** A town with its state, "Bengaluru, Karnataka", or just the town where there's no state or it has the same name. */
internal fun placeLabel(p: Place) = if (p.admin1.isEmpty() || p.admin1 == p.city) p.city else "${p.city}, ${p.admin1}"

/**
 * Where each photo was taken, by media id: the towns Explore has matched so far (off the main thread), empty until then.
 * Photos Explore leaves out (folders hidden from the timeline) have none.
 */
@Composable
internal fun rememberPlaces(): Map<Long, Place> {
    val groups by Explore.places.collectAsState()
    val byId by produceState(emptyMap<Long, Place>(), groups) {
        value = withContext(Dispatchers.Default) { groups.orEmpty().flatMap { g -> g.items.map { it.id to g.key } }.toMap() }
    }
    return byId
}
