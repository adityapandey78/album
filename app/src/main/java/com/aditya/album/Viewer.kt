package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.ContentUris
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
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.zoomable
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
 * More > Rotate, or a two-finger twist, opens rotate mode: the photo turns on screen and nothing is written until Save
 * (see [RotateMode]). A photo that settles on screen is read for QR codes and barcodes (see Codes); a badge opens them.
 * Panoramas get Pan, 360° photos a look-around view (see Panorama); photos of pages, Straighten document (DocumentScan).
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
    onEditDate: ((Media) -> Unit)? = null, // Album's date fixer; no More row for it without one
    onEditPlace: ((Media) -> Unit)? = null,
    onShowOnMap: ((MapFocus) -> Unit)? = null, // the Places map, from the details sheet
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
    val doubleTap = remember(still) { doubleTapZoom(still) } // photos and videos alike
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
    val rotate = rememberRotate()
    val edit = rememberEdit(onMessage = say)
    val clock = rememberClock()
    val placeOf = if (external) emptyMap() else rememberPlaces()
    // Places guessed from camera photos taken around then (location was off): the header leaves them out, Details says so
    val estimated by remember(external) { if (external) flowOf(emptySet()) else SmartIndex.estimatedIds() }.collectAsState(emptySet())
    // An updated original keeps its quality, so it can always be taken back: Undo turns it back the other way
    val rotated = { m: Media, quarters: Int ->
        scope.launch {
            if (snackbar.showSnackbar(rotatedMessage(quarters), "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                CrashLog.note("rotate undo")
                rotate(m, -quarters) { if (it == Turned.Failed) say("Couldn't turn it back") }
            }
        }
    }
    // Text from the photo (TextLens), over the current photo. Swipes and twists wait while it's open.
    var lensOn by remember(current.id) { mutableStateOf(false) }
    val lens = if (lensOn) rememberTextLens(current) else null
    var lensPanel by remember { mutableIntStateOf(0) } // px, to keep the snackbar above it (Straighten document's panel too)
    // Motion photo: its video plays in place over the still, from the Live badge or a long-press
    val motion = rememberMotionVideo(current)
    var playMotion by remember(current.id) { mutableStateOf(false) }
    var motionMuted by remember { mutableStateOf(true) } // a motion photo's own sound: off until turned on while one plays
    // QR codes and barcodes in the photo on screen, read once it has settled there; not RAW photos, which are slow to decode
    val codes = rememberCodes(current, active = !current.isVideo && !isRaw(current.name) && pager.settledPage == pager.currentPage)
    var showCodes by remember { mutableStateOf<List<CodeContent>?>(null) } // the codes' sheet, or one link from the text
    // Panoramas: Pan fills the screen with the photo to drag across, the page staying put. 360° photos: a look-around view.
    val pano = remember(current.id) { if (current.isVideo) null else panoramaOf(current.width, current.height, isScreenshot(current.path, current.name)) }
    val sphere = rememberSphere(current)
    var panning by remember(current.id) { mutableStateOf(false) }
    var lookingAround by remember(current.id) { mutableStateOf(false) }
    // Straighten document, from More: over everything until Discard or Save
    var scan by remember { mutableStateOf<DocumentScan?>(null) }
    // Videos play with sound unless it was turned off on one: then the next ones stay muted, after a restart too
    val prefs = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var videoMuted by remember { mutableStateOf(prefs.getBoolean(VIDEO_MUTED, false)) }

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

    // Rotate mode, from More > Rotate or a twist: the photo turns on screen, and nothing is written until Save
    var rotating by remember { mutableStateOf<RotateMode?>(null) }
    var askSave by remember { mutableStateOf(false) } // the "Save rotated photo" sheet
    // Degrees the current photo is turned on screen, clockwise. A fresh one per photo and per edit: a photo rotated for
    // real reloads upright.
    val angle = remember(current.id, editVersion(current.uri)) { mutableFloatStateOf(0f) }
    val tilted by remember(angle) { derivedStateOf { angle.floatValue != 0f } } // paging and swipe-down wait until it's upright
    val turning = remember { MutatorMutex() } // one turn at a time: a new one takes over from wherever the photo is
    fun turnTo(degrees: Float, then: () -> Unit = {}) {
        scope.launch {
            turning.mutate {
                if (still) angle.floatValue = degrees
                else animate(angle.floatValue, degrees, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { value, _ -> angle.floatValue = value }
                then()
            }
        }
    }
    fun startRotate(m: Media, quarters: Int) {
        CrashLog.note("rotate mode")
        chrome = true // keeps the system bars, so the bar sits just where the dock was
        playMotion = false
        panning = false // rotate mode shows the whole photo
        rotating = RotateMode(m, quarters).also { mode ->
            scope.launch { mode.inPlace = withContext(Dispatchers.IO) { turnsInPlace(context.contentResolver, m.uri) } }
        }
    }
    // Back or Discard: the short way round to upright, then 0, so a full turn counts as none
    fun leaveRotate() {
        rotating = null
        askSave = false
        turnTo(360f * (angle.floatValue / 360f).roundToInt()) { angle.floatValue = 0f }
    }
    val latest by rememberUpdatedState(items)
    // A copy just saved from [m] (rotated or straightened) takes its place once MediaStore lists it: [shown] hears where, or
    // null where it isn't part of what's shown (a collection, search results). Undo deletes the copy: it was only just
    // made, and the original never changed.
    suspend fun showCopy(m: Media, copy: Uri, message: String, shown: suspend (Int?) -> Unit) {
        val id = ContentUris.parseId(copy)
        shown(withTimeoutOrNull(5_000) { snapshotFlow { latest.indexOfFirst { it.id == id } }.first { it >= 0 } })
        if (snackbar.showSnackbar(message, "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            CrashLog.note("copy undo")
            // Back to the original first, or the pager would land on whatever comes after the copy
            if (latest.getOrNull(pager.currentPage)?.id == id) latest.indexOfFirst { it.id == m.id }.takeIf { it >= 0 }?.let { pager.scrollToPage(it) }
            val removed = withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(copy, null, null) > 0 }.getOrDefault(false) }
            if (!removed) say("Couldn't remove the copy")
        }
    }
    // Where the copy isn't shown, the photo turns back instead
    fun saveCopy(mode: RotateMode, note: String? = null) {
        mode.saving = true
        scope.launch {
            val m = mode.m
            val copy = when (val result = saveRotatedCopy(context, m, mode.net)) {
                is RotatedCopy.Failed -> {
                    mode.saving = false // still turned: Save can try again, or Discard
                    say(result.message)
                    return@launch
                }
                is RotatedCopy.Saved -> result
            }
            CrashLog.note("rotated copy saved")
            showCopy(m, copy.uri, copyMessage(copy.album, copy.megapixels, note)) { at ->
                if (at == null) {
                    leaveRotate()
                } else {
                    rotating = null
                    pager.scrollToPage(at)
                }
            }
        }
    }
    fun saveScan(s: DocumentScan) {
        val corners = s.corners.toList()
        if (s.saving || !isPageShape(corners)) return
        s.saving = true
        CrashLog.note("straighten save ${s.cleanup.name}")
        scope.launch {
            when (val result = saveStraightCopy(context, s.m, corners, s.cleanup)) {
                is StraightCopy.Failed -> {
                    s.saving = false // still open: Save can try again, or Discard
                    say(result.message)
                }
                is StraightCopy.Saved -> showCopy(s.m, result.uri, "Saved a straightened copy to ${result.album}") { at ->
                    scan = null
                    at?.let { pager.scrollToPage(it) }
                }
            }
        }
    }
    fun saveOriginal(mode: RotateMode) {
        mode.saving = true
        val m = mode.m
        val quarters = mode.net
        rotate(m, quarters) { result ->
            when (result) {
                Turned.Done -> {
                    rotating = null // the photo reloads upright, with a fresh angle for its new version
                    rotated(m, quarters)
                }
                Turned.Cancelled -> mode.saving = false // Android's pop-up was declined: still turned, so Save can try again
                Turned.Failed -> saveCopy(mode, "The original couldn't be updated")
            }
        }
    }
    fun save(mode: RotateMode) {
        if (mode.saving) return
        mode.saving = true // until it's known what Save does: no second tap meanwhile
        scope.launch {
            val inPlace = mode.inPlace ?: snapshotFlow { mode.inPlace }.filterNotNull().first()
            RotatePrefs.load(context)
            val choice = RotatePrefs.choice.value
            when (saveTarget(choice, inPlace)) {
                RotateSave.Ask -> {
                    mode.saving = false
                    askSave = true
                }
                RotateSave.Copy -> saveCopy(mode, "The original can't be updated".takeIf { choice == RotateSave.Original })
                RotateSave.Original -> saveOriginal(mode)
            }
        }
    }

    // Two-finger twist: the photo follows the fingers ([twisting]: paging and swipe-down wait) and, let go, settles on the
    // nearest quarter. Past halfway to one it opens rotate mode with that turn, or changes the mode's turn.
    var twisting by remember { mutableStateOf(false) }
    var twistFrom by remember { mutableFloatStateOf(0f) } // degrees the photo was turned when this twist began
    val settleTwist by rememberUpdatedState {
        twisting = false
        val quarters = nearestQuarter(angle.floatValue)
        CrashLog.note("twist ${angle.floatValue.roundToInt()}° to $quarters")
        val mode = rotating
        when {
            mode != null -> mode.turns = quarters
            Math.floorMod(quarters, 4) != 0 -> startRotate(current, quarters)
        }
        // Let go near upright, or a full turn round, outside rotate mode: nothing to save, so it's just upright again
        turnTo(quarters * 90f) { if (rotating == null) angle.floatValue = 0f }
    }

    BackHandler(onBack = close)
    BackHandler(enabled = lensOn) { lensOn = false }
    // Leaves rotate mode without saving; while saving, Back waits for it
    BackHandler(enabled = rotating != null) { if (rotating?.saving == false) leaveRotate() }
    BackHandler(enabled = panning) { panning = false }
    BackHandler(enabled = lookingAround) { lookingAround = false }
    BackHandler(enabled = scan != null) { if (scan?.saving == false) scan = null }
    // Its photo went away meanwhile (deleted elsewhere): nothing left to save
    LaunchedEffect(current.id) {
        if (rotating?.m?.id != current.id) {
            rotating = null
            askSave = false
        }
    }
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
            // a turned photo is saved or turned back first; a panorama being panned stays put at its ends
            userScrollEnabled = lens == null && !twisting && rotating == null && !tilted && !panning,
            key = { items[it].id },
        ) { page ->
            val m = items[page]
            val settled = pager.settledPage == page
            // Photos and videos zoom alike, with Telephoto: pinch, pan, double-tap. Rotate edits the file in place under the
            // same uri, and Telephoto keeps its decoded tiles and Exif per uri: a fresh state per edit makes it read the new
            // orientation instead of showing the old one.
            val zoom = key(editVersion(m.uri)) { rememberZoomableImageState() }
            // zoomed in, drags pan the photo or video instead of closing it, and sideways ones only turn the page at its edge
            val zoomed by remember(zoom) { derivedStateOf { (zoom.zoomableState.zoomFraction ?: 0f) > 0.01f } }
            // Left zoomed in, it's back at fit the next time it shows
            LaunchedEffect(settled) { if (!settled) zoom.zoomableState.resetZoom(snap()) }
            val pageLens = lens?.takeIf { m.id == current.id }
            val turnable = m.id == current.id // only the current photo is ever turned (see angle)
            val inRotate = rotating?.m?.id == m.id
            // Rotate mode shows the whole photo: a zoomed one zooms back out
            LaunchedEffect(inRotate) { if (inRotate) zoom.zoomableState.resetZoom(if (still) snap() else spring()) }
            val canTwist = settled && turnable && !m.isVideo && !external && !flying && pageLens == null && rotating?.saving != true && !askSave && !panning
            // Pan: the panorama fills the screen (Telephoto crops it to fit, then pans it) and drags no longer close it
            val panned = panning && m.id == current.id
            LaunchedEffect(panned) { zoom.zoomableState.resetZoom(snap()) } // Pan starts on the whole crop, and leaving it on the whole photo
            Box(
                Modifier.fillMaxSize()
                    // before graphicsLayer, so drag deltas are measured on screen and not inside the moving page
                    .draggable(drag, Orientation.Vertical, enabled = settled && !zoomed && !twisting && lens == null && rotating == null && !tilted && !panned, onDragStopped = release)
                    .then(
                        if (!canTwist) Modifier else Modifier.twistGesture(angle, onTwist = { turned ->
                            val shown = twistAngle(turned)
                            if (shown != 0f && !twisting) {
                                twisting = true
                                twistFrom = angle.floatValue
                                scope.launch { turning.mutate(MutatePriority.UserInput) {} } // the fingers take over from a turn still settling
                            }
                            if (twisting) {
                                val to = twistFrom + shown
                                if (reachesQuarter(angle.floatValue, to)) haptics.tick()
                                angle.floatValue = to
                            }
                        }, onEnd = { if (twisting) settleTwist() }),
                    )
                    .then(if (pageLens == null) Modifier else Modifier.lensSelection(pageLens, { zoom.zoomableState.transformedContentBounds }, haptics))
                    .graphicsLayer {
                        if (!settled) return@graphicsLayer
                        translationY = dragY
                        val turned = if (turnable) angle.floatValue else 0f
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
                        zoom = zoom.zoomableState,
                        doubleTap = doubleTap,
                        muted = videoMuted,
                        onSound = {
                            videoMuted = !videoMuted
                            CrashLog.note("video sound ${if (videoMuted) "off" else "on"}")
                            prefs.edit { putBoolean(VIDEO_MUTED, videoMuted) }
                        },
                        onTap = { chrome = !chrome },
                        onHideChrome = { chrome = false },
                    )
                } else {
                    // the grid's thumbnail (same Coil memory-cache key, see ThumbKeyer) shows until the full photo is ready
                    val request = remember(m.uri, editVersion(m.uri)) {
                        if (isRaw(m.name)) ImageRequest.Builder(context).raw(m.uri, thumb = false).placeholderMemoryCacheKey(rawThumbKey(m.uri)).build()
                        else ImageRequest.Builder(context).data(m.uri).placeholderMemoryCacheKey("${m.uri}#${editVersion(m.uri)}").build()
                    }
                    val video = motion?.takeIf { m.id == current.id }
                    key(editVersion(m.uri)) { // rebuilt after an edit, like reopening the photo, so nothing stale is drawn
                    ZoomableAsyncImage(
                        request,
                        m.name,
                        Modifier.fillMaxSize(),
                        state = zoom,
                        contentScale = if (panned) ContentScale.Crop else ContentScale.Fit,
                        onClick = { if (pageLens != null) pageLens.selection = null else chrome = !chrome },
                        onDoubleClick = doubleTap,
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
                    // Rotate mode only turns the photo, so its pinch, pan and taps wait. A twist still works: the page above reads it.
                    if (inRotate) Box(Modifier.matchParentSize().pointerInput(Unit) {})
                }
            }
        }

        if (flying) {
            val m = current
            // Same size as the grid requested, so Coil serves the thumbnail from memory with no blank frame
            val px = cell?.let { maxOf(it.width, it.height).toInt() - 1 } ?: 512
            val thumb = remember(m.id, px) {
                (if (isRaw(m.name)) ImageRequest.Builder(context).raw(m.uri, thumb = true) else ImageRequest.Builder(context).data(Thumb(m.uri))).size(px, px).build()
            }
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
        if (lookingAround && sphere != null) SphereView(current, sphere, onClose = { lookingAround = false })

        val bars = chrome && !flying && lens == null && rotating == null && !lookingAround && scan == null
        AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter).then(fade), enter = fadeIn(), exit = fadeOut()) {
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
                if (isRaw(current.name)) Badge("RAW", tone = BadgeTone.Photo)
                codes?.takeIf { it.isNotEmpty() }?.let { found ->
                    HeaderBadge(codesBadge(found), Sym.QrCode2, "Open") {
                        CrashLog.note("codes ${found.size}")
                        showCodes = found
                    }
                }
                when {
                    sphere != null -> HeaderBadge("360°", Sym.Panorama, "Look around") {
                        CrashLog.note("look around")
                        lookingAround = true
                    }
                    pano != null -> HeaderBadge("Pan", Sym.Panorama, if (panning) "Fit to screen" else "Pan", active = panning) {
                        CrashLog.note("pan ${!panning}")
                        panning = !panning
                    }
                }
                // A motion photo's sound, while it plays. A video's is with its own controls (see VideoPage).
                if (playMotion) {
                    AlbumIconButton(
                        if (motionMuted) Sym.VolumeOff else Sym.VolumeUp,
                        "Sound",
                        {
                            CrashLog.note("motion sound ${if (motionMuted) "on" else "off"}")
                            motionMuted = !motionMuted
                        },
                        Modifier.semantics { stateDescription = if (motionMuted) "Off" else "On" },
                        variant = IconButtonVariant.Photo,
                    )
                }
            }
        }

        AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter).then(fade), enter = fadeIn(), exit = fadeOut()) {
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
                if (!external) {
                    onTrash?.let { trash ->
                        AlbumIconButton(Sym.Delete, "Move to trash", {
                            CrashLog.note("trash 1")
                            trash(current)
                        })
                    }
                }
                // Last, as in the design: Details first inside, then what's used less often
                AlbumIconButton(Sym.MoreVert, "More", {
                    CrashLog.note("more")
                    showMore = true
                })
            }
        }

        // Rotate mode's bar takes the dock's place. It goes at once when the mode ends, as the dock fades back.
        AnimatedVisibility(rotating != null && !flying, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = ExitTransition.None) {
            rotating?.let { mode ->
                RotateBar(
                    mode,
                    onTurn = { quarters ->
                        mode.turns += quarters
                        turnTo(mode.turns * 90f)
                    },
                    onDiscard = {
                        CrashLog.note("rotate discard")
                        leaveRotate()
                    },
                    onSave = { save(mode) },
                    modifier = Modifier.navigationBarsPadding().padding(bottom = dockGap),
                )
            }
        }

        if (lens != null) {
            TextLensBar(onClose = { lensOn = false }, Modifier.align(Alignment.TopCenter))
            TextLensPanel(lens, onCopied = { say("Copied") }, onLink = { showCodes = listOf(it) }, Modifier.align(Alignment.BottomCenter).onSizeChanged { lensPanel = it.height })
        }

        scan?.let { s ->
            DocumentScreen(s, onDiscard = {
                CrashLog.note("straighten discard")
                scan = null
            }, onSave = { saveScan(s) }, onPanel = { lensPanel = it })
        }

        val above = if (lens != null || scan != null) Modifier.padding(bottom = with(density) { lensPanel.toDp() } + Space.s3)
        else Modifier.navigationBarsPadding().padding(bottom = viewerSnackbarGap)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).then(above)) { AlbumSnackbar(it) }
    }
    if (showInfo) InfoSheet(current, placeOf[current.id], onShowOnMap.takeUnless { external }) { showInfo = false }
    if (showMore) {
        val title = if (current.taken > 0) "${dayTitle(current.day)} · ${clock(current.taken)}" else current.name
        val m = current
        MoreSheet(
            m,
            title,
            external,
            onDetails = {
                CrashLog.note("details")
                showInfo = true
            },
            onText = {
                CrashLog.note("text lens")
                chrome = true
                lensOn = true
            },
            onRotate = { startRotate(m, 0) },
            onStraighten = {
                CrashLog.note("straighten")
                chrome = true
                panning = false
                scan = DocumentScan(m)
            },
            onEditDate = onEditDate?.let { edit -> { edit(m) } },
            onEditPlace = onEditPlace?.let { edit -> { edit(m) } },
            onMoveToSafe = onMoveToSafe?.let { safe ->
                {
                    CrashLog.note("move to safe")
                    safe(m)
                }
            },
            onDismiss = { showMore = false },
        )
    }
    showCodes?.let { CodesSheet(it, onCopied = { say("Copied") }, onDismiss = { showCodes = null }) }
    rotating?.takeIf { askSave }?.let { mode ->
        SaveRotatedSheet(mode.inPlace == true, onDismiss = { askSave = false }) { choice, always ->
            askSave = false
            CrashLog.note("rotate save ${choice.name}${if (always) ", always" else ""}")
            if (always) RotatePrefs.set(choice)
            if (choice == RotateSave.Original) saveOriginal(mode) else saveCopy(mode)
        }
    }
}

/**
 * The viewer's More sheet: Details first, then actions used less often than the dock's. Text, Rotate and Use as are for
 * photos in Album only; a file from another app ([external]) has Details alone. Straighten document only shows for what
 * may be a page (see mayBePage).
 */
@Composable
private fun MoreSheet(
    m: Media,
    title: String,
    external: Boolean,
    onDetails: () -> Unit,
    onText: () -> Unit,
    onRotate: () -> Unit,
    onStraighten: () -> Unit,
    onEditDate: (() -> Unit)?,
    onEditPlace: (() -> Unit)?,
    onMoveToSafe: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val page by produceState(false, m.id) { value = !external && mayBePage(m) }
    Sheet(onDismiss, title = title) {
        // Each row closes the sheet, then acts
        fun closing(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        MenuRow(Sym.Info, "Details", closing(onDetails), sub = "Camera, place and file. Or swipe up")
        if (!m.isVideo && !external) {
            // Read on the phone, offline: nothing leaves it
            MenuRow(Sym.TextFields, "Text", closing(onText), sub = "Select and copy words in this photo")
            if (page) MenuRow(Sym.Description, "Straighten document", closing(onStraighten), sub = "A flat copy of just the page")
            MenuRow(Sym.RotateRight, "Rotate", closing(onRotate), sub = "Or twist the photo with two fingers")
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
        if (!external) {
            onEditDate?.let { MenuRow(Sym.Schedule, "Edit date and time", closing(it), sub = "For a photo saved with the wrong date") }
            onEditPlace?.let { MenuRow(Sym.LocationOn, "Edit place", closing(it), sub = "Set where it was taken, or remove it") }
        }
        onMoveToSafe?.let { MenuRow(Sym.Lock, "Move to Private safe", closing(it)) }
    }
}

/**
 * A badge in the viewer's top bar that does something, as the Live badge plays: the badge itself is small, its touch
 * target the full 48dp. [active] fills it in flash while what it turns on is on.
 */
@Composable
private fun HeaderBadge(text: String, icon: Sym, clickLabel: String, active: Boolean? = null, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = Space.target).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .then(if (active == null) Modifier else Modifier.semantics { stateDescription = if (active) "On" else "Off" }),
        contentAlignment = Alignment.Center,
    ) { Badge(text, tone = if (active == true) BadgeTone.Flash else BadgeTone.Photo, icon = icon) }
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

// ---------- Zoom ----------

/** Double-tap zoom for photos and videos: Telephoto's own (all the way in, or back out to fit), at once with Remove animations. */
private fun doubleTapZoom(still: Boolean): DoubleClickToZoomListener = if (still) InstantDoubleTapZoom else DoubleClickToZoomListener.cycle()

/** Telephoto's cycle without its animation: back out to fit once (nearly) all the way in, else in to the most. */
private object InstantDoubleTapZoom : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        val fraction = state.zoomFraction ?: return // nothing to zoom yet
        if (fraction >= 0.95f) state.resetZoom(snap()) else state.zoomTo(state.zoomSpec.maximum.factor, centroid, snap())
    }
}

// ---------- Video ----------

/** The player's own controls used to hide this long into playback; the viewer's chrome does the same. */
private const val CONTROLS_MS = 5_000L

/** The "settings" prefs key that remembers videos were muted. */
private const val VIDEO_MUTED = "video_muted"

/**
 * Plays only while it's the settled page; the player is released as soon as you swipe away. Tapping toggles the chrome;
 * with it comes a big play/pause in the middle. Always there: a progress line in flash along the video's bottom edge,
 * which seeks when dragged or tapped, and above its end the sound button ([muted], [onSound]), unless the video is silent.
 * The picture zooms as a photo does ([zoom], the page's, and [doubleTap]); the controls stay as they are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoPage(
    m: Media,
    active: Boolean,
    chrome: Boolean,
    zoom: ZoomableState,
    doubleTap: DoubleClickToZoomListener,
    muted: Boolean,
    onSound: () -> Unit,
    onTap: () -> Unit,
    onHideChrome: () -> Unit,
) {
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
    var hasSound by remember(m.id) { mutableStateOf(false) } // known once the player has read the file's tracks
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

                override fun onTracksChanged(tracks: Tracks) {
                    hasSound = tracks.containsType(C.TRACK_TYPE_AUDIO)
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
        val density = LocalDensity.current
        // Where the picture is drawn: fitted and centred, as the player draws it
        val frame = with(density) { fitRect(shape.first, shape.second, Size(maxWidth.toPx(), maxHeight.toPx())) }
        // Told to the zoom, so it stops at the picture's edges and a double-tap zooms in where it lands. Off until the
        // video's size is known.
        val known = shape.first > 0 && shape.second > 0
        LaunchedEffect(zoom, frame.size, known) { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(frame.size.takeIf { known })) }
        // Only the picture zooms and pans; the controls drawn after it keep their size and place. Its tap shows or hides the chrome.
        Box(Modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() }, onDoubleClick = doubleTap)) {
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
            // Above the player view, which would otherwise take the touches; the zoom around them still sees them all
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
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

        // The line runs along the bottom of the picture, but never so low that its touch strip (a full target tall) meets
        // the dock: seeking works with the dock showing, as it is while paused. Measured as if the navigation bar showed,
        // so neither it nor the sound button moves when the chrome comes and goes.
        val lowest = with(density) {
            maxHeight.toPx() - WindowInsets.navigationBarsIgnoringVisibility.getBottom(this) - (dockGap + Space.bar + Space.target / 2).toPx()
        }
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
        // Sound on or off, one tap away all through playback: just above the line's end, inside the picture, and clear of
        // a curved screen edge. A video with no sound track gets none, rather than a button that does nothing.
        if (hasSound) {
            AlbumIconButton(
                if (muted) Sym.VolumeOff else Sym.VolumeUp,
                "Sound",
                onSound,
                Modifier
                    .offset {
                        val right = minOf(frame.right - Space.s3.toPx(), constraints.maxWidth - Space.edgeSafe.toPx())
                        IntOffset((right - Space.target.toPx()).roundToInt(), (lineY - Space.s3.toPx() - Space.target.toPx()).roundToInt())
                    }
                    .semantics { stateDescription = if (muted) "Off" else "On" },
                variant = IconButtonVariant.Photo,
            )
        }
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
private fun InfoSheet(m: Media, place: Place?, onShowOnMap: ((MapFocus) -> Unit)?, onDismiss: () -> Unit) {
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
                if (latLng != null && onShowOnMap != null) {
                    AlbumButton(
                        "Show on map", { onShowOnMap(MapFocus(m.id, latLng[0], latLng[1])) }, Modifier.offset(x = -Space.s3),
                        variant = ButtonVariant.Quiet, icon = Sym.Map,
                    )
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
