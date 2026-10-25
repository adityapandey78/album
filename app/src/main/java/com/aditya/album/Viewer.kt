package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** The dock floats this far above the navigation bar (the design's 28px). */
private val dockGap = Space.s6 + Space.s1

/** Where snackbars sit over the viewer, above the navigation bar: just clear of the dock. For the shell's Undo too. */
val viewerSnackbarGap = dockGap + Space.bar + Space.s3

/** Progress lines (the video's, a story's bars) are this thick, as in the design. */
internal val progressLine = 3.dp

/**
 * Full-screen swipeable viewer. [items] is the list it was opened from (all photos, or one album).
 * [external] is a file another app opened: it may not be in MediaStore, so only Share, Edit and Details are offered.
 * A two-finger twist turns a photo; let go past halfway to a quarter turn and it's rotated for real (see [rememberRotate]).
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
    onMoveToSafe: ((Media) -> Unit)? = null, // the Private safe; no More row for it without one
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
    // Places guessed from camera photos taken around then (location was off): the header leaves them out, Details says so
    val estimated by remember(external) { if (external) flowOf(emptySet()) else SmartIndex.estimatedIds() }.collectAsState(emptySet())
    // Rotate keeps quality, so it can always be taken back: Undo turns the photo back the other way
    val rotated = { m: Media, quarters: Int ->
        scope.launch {
            val message = when (Math.floorMod(quarters, 4)) {
                1 -> "Rotated right"
                3 -> "Rotated left"
                else -> "Rotated 180°"
            }
            if (snackbar.showSnackbar(message, "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                CrashLog.note("rotate undo")
                rotate(m, -quarters) {}
            }
        }
    }
    // Text from the photo (TextLens), over the current photo. Swipes and twists wait while it's open.
    var lensOn by remember(current.id) { mutableStateOf(false) }
    val lens = if (lensOn) rememberTextLens(current) else null
    var lensPanel by remember { mutableIntStateOf(0) } // px, to keep the snackbar above it
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

    // Two-finger twist: [twisting] while the fingers turn the photo (the pager and swipe-down wait), [turning] while a
    // released twist settles and Rotate writes it (no new twist meanwhile)
    var twisting by remember { mutableStateOf(false) }
    var turning by remember { mutableStateOf(false) }
    val settleTwist by rememberUpdatedState { m: Media, angle: MutableFloatState ->
        twisting = false
        turning = true
        val quarters = nearestQuarter(angle.floatValue)
        CrashLog.note("twist ${angle.floatValue.roundToInt()}° to $quarters")
        scope.launch {
            suspend fun springTo(target: Float) {
                if (still) angle.floatValue = target
                else animate(angle.floatValue, target, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { value, _ -> angle.floatValue = value }
            }
            springTo(quarters * 90f)
            if (Math.floorMod(quarters, 4) == 0) {
                angle.floatValue = 0f // a full turn looks the same as none
                turning = false
                return@launch
            }
            // Held at the quarter turn while Android asks and the file is written; the reloaded photo is then upright
            rotate(m, quarters) { ok ->
                turning = false
                if (ok) {
                    angle.floatValue = 0f
                    rotated(m, quarters)
                } else {
                    scope.launch { springTo(0f) } // refused or cancelled: Rotate has said why
                }
            }
        }
    }

    BackHandler(onBack = close)
    BackHandler(enabled = lensOn) { lensOn = false }
    ImmersiveBars(visible = chrome)

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
            userScrollEnabled = lens == null && !twisting && !turning, // stays on a photo being turned until it's written
            key = { items[it].id },
        ) { page ->
            val m = items[page]
            val settled = pager.settledPage == page
            // Rotate edits the file in place under the same uri, and Telephoto keeps its decoded tiles and Exif per uri:
            // a fresh state per edit makes it read the new orientation instead of showing the old one
            val zoom = key(editVersion(m.uri)) { rememberZoomableImageState() }
            // zoomed in, vertical drags pan the photo (Telephoto) instead of closing it
            val zoomed by remember(zoom) { derivedStateOf { (zoom.zoomableState.zoomFraction ?: 0f) > 0.01f } }
            val pageLens = lens?.takeIf { m.id == current.id }
            // Degrees a twist has turned the photo, clockwise. A fresh one per edit: once rotated for real it's upright again.
            val angle = remember(m.id, editVersion(m.uri)) { mutableFloatStateOf(0f) }
            val canTwist = settled && !m.isVideo && !external && !flying && pageLens == null && !turning
            Box(
                Modifier.fillMaxSize()
                    // before graphicsLayer, so drag deltas are measured on screen and not inside the moving page
                    .draggable(drag, Orientation.Vertical, enabled = settled && !zoomed && !twisting && lens == null, onDragStopped = release)
                    .then(
                        if (!canTwist) Modifier else Modifier.twistGesture(angle, onTwist = { turned ->
                            val shown = twistAngle(turned)
                            if (shown != 0f) twisting = true
                            if (reachesQuarter(angle.floatValue, shown)) haptics.tick()
                            angle.floatValue = shown
                        }, onEnd = { if (twisting) settleTwist(m, angle) }),
                    )
                    .then(if (pageLens == null) Modifier else Modifier.lensSelection(pageLens, { zoom.zoomableState.transformedContentBounds }, haptics))
                    .graphicsLayer {
                        if (!settled) return@graphicsLayer
                        translationY = dragY
                        val turned = angle.floatValue
                        // turned, it shrinks to fit (or grows), ending at the size it has once rotated for real
                        val fit = if (turned == 0f) 1f else twistScale(fitRect(m.width, m.height, size).size, size, turned)
                        rotationZ = turned
                        scaleX = (1f - 0.25f * pulled()) * fit
                        scaleY = scaleX
                    },
            ) {
                if (m.isVideo) {
                    VideoPage(
                        m,
                        active = settled,
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
                        onClick = { if (pageLens != null) pageLens.selection = null else chrome = !chrome },
                        onLongClick = video?.takeIf { pageLens == null }?.let {
                            { _: Offset ->
                                CrashLog.note("motion play (hold)")
                                haptics.longPress()
                                playMotion = true
                            }
                        },
                    )
                    }
                    if (video != null && playMotion) MotionPlayer(m.uri, video, motionMuted, onEnd = { playMotion = false })
                    if (pageLens != null) TextLensHighlights(pageLens) { zoom.zoomableState.transformedContentBounds }
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
        AnimatedVisibility(chrome && !flying && lens == null, Modifier.align(Alignment.TopCenter).then(fade), enter = fadeIn(), exit = fadeOut()) {
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
                        val place = placeOf[current.id]?.takeIf { current.id !in estimated }?.city
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
            }
        }

        AnimatedVisibility(chrome && !flying && lens == null, Modifier.align(Alignment.BottomCenter).then(fade), enter = fadeIn(), exit = fadeOut()) {
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
                AlbumIconButton(Sym.Edit, "Edit", { edit(current) })
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
                    // Last, as in the design. A video has nothing in it but the safe.
                    if (!current.isVideo || onMoveToSafe != null) {
                        AlbumIconButton(Sym.MoreVert, "More", {
                            CrashLog.note("more")
                            showMore = true
                        })
                    }
                }
            }
        }

        if (lens != null) {
            TextLensBar(onClose = { lensOn = false }, Modifier.align(Alignment.TopCenter))
            TextLensPanel(lens, onCopied = { say("Copied") }, Modifier.align(Alignment.BottomCenter).onSizeChanged { lensPanel = it.height })
        }

        val above = if (lens != null) Modifier.padding(bottom = with(density) { lensPanel.toDp() } + Space.s3)
        else Modifier.navigationBarsPadding().padding(bottom = viewerSnackbarGap)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).then(above)) { AlbumSnackbar(it) }
    }
    if (showInfo) InfoSheet(current, placeOf[current.id]) { showInfo = false }
    if (showMore) {
        val title = if (current.taken > 0) "${dayTitle(current.day)} · ${clock(current.taken)}" else current.name
        val m = current
        MoreSheet(
            m,
            title,
            onText = {
                CrashLog.note("text lens")
                chrome = true
                lensOn = true
            },
            onRotate = { quarters -> rotate(m, quarters) { ok -> if (ok) rotated(m, quarters) } },
            onMoveToSafe = onMoveToSafe?.let { safe ->
                {
                    CrashLog.note("move to safe")
                    safe(m)
                }
            },
            onDismiss = { showMore = false },
        )
    }
}

/** The viewer's More sheet: actions used less often than the dock's. Text, Rotate and Use as are for photos only. */
@Composable
private fun MoreSheet(
    m: Media,
    title: String,
    onText: () -> Unit,
    onRotate: (quarters: Int) -> Unit,
    onMoveToSafe: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Sheet(onDismiss, title = title) {
        // Each row closes the sheet, then acts
        fun closing(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        if (!m.isVideo) {
            // Read on the phone, offline: nothing leaves it
            MenuRow(Sym.TextFields, "Text", closing(onText), sub = "Select and copy words in this photo")
            MenuRow(Sym.RotateRight, "Rotate right", closing { onRotate(1) }, sub = "Lossless, keeps quality. Or twist with two fingers")
            MenuRow(Sym.RotateLeft, "Rotate left", closing { onRotate(-1) })
            MenuRow(Sym.Wallpaper, "Use as", closing {
                CrashLog.note("use as")
                // Wallpaper, contact photo and the like. Some of those apps read the type from the "mimeType" extra.
                val attach = Intent(Intent.ACTION_ATTACH_DATA)
                    .setDataAndType(m.uri, "image/*")
                    .putExtra("mimeType", "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startSafely(Intent.createChooser(attach, "Use as"))
            }, sub = "Wallpaper, contact photo")
        }
        onMoveToSafe?.let { MenuRow(Sym.Lock, "Move to Private safe", closing(it)) }
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

// ---------- Twist ----------

/** A twist starts turning the photo only past this many degrees, so a pinch (fingers always twist a little) never does. */
private const val TWIST_START = 15f

/** What a twist of [turned] degrees shows: nothing within [TWIST_START], then the rest, so the photo never jumps. */
fun twistAngle(turned: Float): Float = when {
    turned > TWIST_START -> turned - TWIST_START
    turned < -TWIST_START -> turned + TWIST_START
    else -> 0f
}

/** Quarter turns (clockwise positive) nearest [degrees]: where a released twist settles. */
fun nearestQuarter(degrees: Float): Int = (degrees / 90f).roundToInt()

/** Whether turning from [from] to [to] degrees reaches a quarter turn (0, ±90, ±180...), other than one it starts on. */
fun reachesQuarter(from: Float, to: Float): Boolean = when {
    to > from -> floor(to / 90f) > floor(from / 90f)
    to < from -> ceil(to / 90f) < ceil(from / 90f)
    else -> false
}

/**
 * How much a photo drawn [photo] big inside [box] is scaled while turned [degrees], so it always fits: 1 upright, and at
 * a quarter turn exactly the size it will have once rotated for real, so nothing jumps when it reloads.
 */
fun twistScale(photo: Size, box: Size, degrees: Float): Float {
    if (photo.width <= 0f || photo.height <= 0f) return 1f
    val radians = Math.toRadians(degrees.toDouble())
    val cos = abs(cos(radians)).toFloat()
    val sin = abs(sin(radians)).toFloat()
    return minOf(box.width / (photo.width * cos + photo.height * sin), box.height / (photo.width * sin + photo.height * cos))
}

/**
 * Follows two fingers turning: [onTwist] gets the degrees turned (clockwise positive) since the second finger went down,
 * [onEnd] comes once fewer than two are left. It looks at touches before the photo does and takes none, so Telephoto's
 * pinch and pan see every touch as usual. Restarts with a fresh [key].
 */
private fun Modifier.twistGesture(key: Any, onTwist: (Float) -> Unit, onEnd: () -> Unit) = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var turned = 0f
        var twoFingers = false
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    twoFingers = true
                    turned += event.calculateRotation()
                    onTwist(turned)
                } else if (twoFingers) {
                    twoFingers = false
                    turned = 0f
                    onEnd()
                }
            } while (event.changes.any { it.pressed })
        } finally {
            if (twoFingers) onEnd() // cancelled mid-twist: settle rather than leave the photo askew
        }
    }
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
    // The index's own place for this photo: down to the locality, and whether it was estimated from photos taken nearby in
    // time (a photo without GPS), which must never read as where it was taken
    val indexed by produceState<ItemPlace?>(null, m.id) {
        value = withContext(Dispatchers.IO) { runCatching { SmartIndex.placeOf(m.id) }.getOrNull() }
    }
    val where = indexed?.place ?: place
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
        if (where != null || latLng != null) {
            InfoRow(Sym.LocationOn) {
                val coords = latLng?.let { coordinates(it[0], it[1]) }
                Text(where?.let(::placeLabel) ?: coords.orEmpty(), style = type.label, color = c.ink)
                when {
                    indexed?.estimated == true -> Text("Estimated from photos taken around the same time", style = type.meta, color = c.inkMuted)
                    where != null && coords != null -> Text(coords, style = type.meta, color = c.inkMuted)
                }
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

/**
 * Where a photo was, from the neighbourhood up: "Baga, Calangute, Goa", "Bengaluru, Karnataka", or just the town where
 * there's no state or it has the same name. Places without a locality (Explore, slideshows) start at the town.
 */
internal fun placeLabel(p: Place) = listOf(p.locality, p.city, p.admin1).filter { it.isNotEmpty() }.distinct().joinToString(", ")

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
