package com.aditya.album

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.roundToInt

// A video in the viewer, with the design's "clean" controls: a glass row with play, the times, a track to scrub (a frame
// peek above the thumb while dragging), speed and sound. Hold the video for 2x, double-tap a side to skip 10 s; a note
// says what a gesture or a button just did.

/** The controls hide this long into playback, with the rest of the viewer's chrome. */
private const val CONTROLS_MS = 5_000L

/** How long a note stays up. */
private const val NOTE_MS = 800L

/** A double-tap on either side skips this far. */
private const val SKIP_MS = 10_000L

/** Holding the video plays it this fast. */
private const val HOLD_SPEED = 2f

/** The speed button's choices. */
private val speeds = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

/** The scrub peek: a frame this big above the thumb. */
private val peekWidth = 96.dp
private val peekHeight = 64.dp

/** The peek follows the finger to the nearest this many ms, so a slow drag doesn't decode the same key frame again. */
private const val PEEK_STEP_MS = 250L

/** The glass row is this tall; its buttons keep their full 48dp. */
private val barHeight = 52.dp

/** The filmstrip: this many frames, each this wide with this gap, in a strip this tall; decoded to fit this square. */
const val STRIP_FRAMES = 16
private val stripFrame = 40.dp
private val stripGap = 2.dp
private val stripHeight = 56.dp
private val stripDecode = 64.dp

/** The whole filmstrip's length: the video's start to its end. */
private val stripLength = (stripFrame + stripGap) * STRIP_FRAMES

/**
 * Where the video is (0 to 1) after dragging the filmstrip [dragged] px from where it was at [from], on a strip [length]
 * px long for the whole video. Dragging left moves later, as the frames slide under the fixed playhead.
 */
fun stripFraction(from: Float, dragged: Float, length: Float): Float =
    if (length <= 0f) from else (from - dragged / length).coerceIn(0f, 1f)

/** The time (ms) frame [k] of the filmstrip shows: the middle of its share of a video [length] ms long. */
fun stripFrameAt(k: Int, length: Long): Long = length * (2 * k + 1) / (2 * STRIP_FRAMES)

/** A playback speed as the speed button shows it: "0.5×", "1×", "1.25×". */
fun speedLabel(speed: Float): String = (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"

/** Which way a double-tap at [x] (0 to 1 across the video) skips: -1 back, 1 forward, 0 the middle, which zooms instead. */
fun skipSide(x: Float): Int = when {
    x < 0.4f -> -1
    x > 0.6f -> 1
    else -> 0
}

/** One video's playback as its controls see it. [progress] (0 to 1) changes every frame: read it only while drawing. */
@Stable
private class VideoState(length: Long) {
    var playing by mutableStateOf(true) // wants to play: paused and finished are both false
    var length by mutableLongStateOf(length) // ms; the file's own once the player has read it
    val progress = mutableFloatStateOf(0f)
    var speed by mutableFloatStateOf(1f)
    var loop by mutableStateOf(false)
    var held by mutableStateOf(false) // pressed and held: plays at HOLD_SPEED until let go
    var speedOpen by mutableStateOf(false)
    var scrubbing by mutableStateOf(false)
    var hasSound by mutableStateOf(false) // known once the player has read the file's tracks
    var resume = false // whether a scrub plays on once it's let go

    /** Where the video is, in ms. */
    val at get() = (progress.floatValue * length).toLong()
}

/** What a note over the video says, and where: the middle, a side (a skip) or the top (holding). [flip] mirrors the icon. */
private class Note(val text: String, val icon: Sym, val place: Alignment, val flip: Boolean = false)

/** The hold note, under the top bar while the video is held. */
private val holdNote = Note("${speedLabel(HOLD_SPEED)} while you hold", Sym.FastForward, Alignment.TopCenter)

/**
 * A video page. It plays only while it's the settled page ([active]); the player is released as soon as you swipe away.
 * Tap shows or hides the [chrome], which brings the video bar with it; with the chrome hidden a progress line in flash
 * runs along the picture's bottom edge. Hold for 2x until let go; double-tap the left or right 40% to skip 10 s (the
 * middle, or anywhere while zoomed in, zooms as a photo does: [zoom], [doubleTap]). [muted] and [onSound] are the
 * viewer's, remembered across videos. The bar shows only while [bar]: not under Details, nor while the photo is pulled down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoPage(
    m: Media,
    active: Boolean,
    chrome: Boolean,
    zoom: ZoomableState,
    doubleTap: DoubleClickToZoomListener,
    muted: Boolean,
    onSound: () -> Unit,
    onTap: () -> Unit,
    onHideChrome: () -> Unit,
    bar: Boolean,
) {
    if (!active) {
        AsyncImage(Thumb(m.uri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val haptics = rememberHaptics()
    // Hardware decoders occasionally choke on odd files; if that happens, retry once with the software decoder.
    var softwareOnly by remember(m.id) { mutableStateOf(false) }
    var failed by remember(m.id) { mutableStateOf(false) }
    // As shown: the player turns sideways-stored phone videos upright, which MediaStore's width and height may not say
    var shape by remember(m.id) { mutableStateOf(m.width to m.height) }
    // Plays by itself and loops short clips as Settings > Photos and videos says
    val v = remember(m.id) {
        VideoState(m.durationMs).apply {
            playing = PlayPrefs.autoplay.get(context)
            loop = PlayPrefs.loops(context, m.durationMs)
        }
    }
    var note by remember(m.id) { mutableStateOf<Note?>(null) }
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
                    v.playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                    player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { v.length = it }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0) shape = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt() to videoSize.height
                }

                override fun onTracksChanged(tracks: Tracks) {
                    v.hasSound = tracks.containsType(C.TRACK_TYPE_AUDIO)
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
            playWhenReady = v.playing
        }
    }
    // The player skips each of these when it's already so
    SideEffect {
        player.volume = if (muted) 0f else 1f
        player.setPlaybackSpeed(if (v.held) HOLD_SPEED else v.speed)
        player.repeatMode = if (v.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(chrome, v.playing, v.scrubbing, v.speedOpen) {
        if (chrome && v.playing && !v.scrubbing && !v.speedOpen) {
            delay(CONTROLS_MS)
            onHideChrome()
        }
    }
    LaunchedEffect(chrome) { if (!chrome) v.speedOpen = false }
    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MS)
            note = null
        }
    }
    // Every frame while playing, but read only while drawing, so playback never recomposes
    LaunchedEffect(player, v.playing) {
        while (true) {
            if (v.length > 0) v.progress.floatValue = (player.currentPosition.toFloat() / v.length).coerceIn(0f, 1f)
            if (!v.playing) break
            withFrameMillis { }
        }
    }
    val seek = { fraction: Float ->
        if (v.length > 0) {
            player.seekTo((fraction * v.length).toLong())
            v.progress.floatValue = fraction
        }
    }
    val play = {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }
    val skip by rememberUpdatedState { side: Int ->
        if (v.length > 0) {
            CrashLog.note("video skip $side")
            haptics.tick()
            seek(((v.at + side * SKIP_MS).coerceIn(0, v.length)).toFloat() / v.length)
            note = if (side < 0) Note("−10 s", Sym.Replay10, Alignment.TopStart) else Note("+10 s", Sym.Forward10, Alignment.TopEnd)
        }
    }
    val peek = rememberPeek(m.uri, v.scrubbing) { v.at }
    val controls = AlbumTheme.spec.appearance.videoControls
    val strip = rememberStrip(m.uri, controls == VideoControls.Filmstrip)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Where the picture is drawn: fitted and centred, as the player draws it
        val frame = with(density) { fitRect(shape.first, shape.second, Size(maxWidth.toPx(), maxHeight.toPx())) }
        // Told to the zoom, so it stops at the picture's edges and a double-tap zooms in where it lands. Off until the
        // video's size is known.
        val known = shape.first > 0 && shape.second > 0
        LaunchedEffect(zoom, frame.size, known) { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(frame.size.takeIf { known })) }
        val width by rememberUpdatedState(constraints.maxWidth.toFloat())
        // A side skips, unless zoomed in: then a double-tap zooms back out, as it does in the middle
        val skipOrZoom = remember(doubleTap) {
            object : DoubleClickToZoomListener {
                override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
                    val side = skipSide(centroid.x / width)
                    if (side == 0 || (state.zoomFraction ?: 0f) > 0.01f) doubleTap.onDoubleClick(state, centroid) else skip(side)
                }
            }
        }
        // Only the picture zooms and pans; the controls drawn after it keep their size and place
        Box(
            Modifier.fillMaxSize()
                .holdGesture(player) { held ->
                    if (held) {
                        CrashLog.note("video hold")
                        haptics.longPress()
                        if (!v.playing) play()
                    }
                    v.held = held
                }
                .zoomable(zoom, onClick = { onTap() }, onDoubleClick = skipOrZoom),
        ) {
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
        // With the chrome hidden, how far it has played: a line along the picture's bottom edge
        if (!chrome) {
            Spacer(
                Modifier.matchParentSize().drawBehind {
                    val h = progressLine.toPx()
                    drawRect(c.flash, Offset(frame.left, frame.bottom - h), Size(frame.width * v.progress.floatValue, h))
                },
            )
        }
        // The design's 12dp drop as it hides
        val drop = with(density) { 12.dp.roundToPx() }
        AnimatedVisibility(
            chrome && bar,
            // Above the dock, measured as if the navigation bar showed, so it never moves when the chrome comes and goes
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(start = Space.s3, end = Space.s3, bottom = dockGap + Space.bar + Space.s3),
            enter = if (still) EnterTransition.None else fadeIn(tween(200)) + slideInVertically(tween(240, easing = barEase)) { drop },
            exit = if (still) ExitTransition.None else fadeOut(tween(200)) + slideOutVertically(tween(240, easing = barEase)) { drop },
        ) {
            VideoBar(
                v,
                controls,
                muted,
                peek,
                strip,
                onPlay = {
                    CrashLog.note(if (v.playing) "video pause" else "video play")
                    if (v.playing) player.pause() else play()
                },
                onScrubStart = { fraction ->
                    v.resume = v.playing
                    v.scrubbing = true
                    player.pause()
                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC) // fast enough to follow the finger
                    seek(fraction)
                },
                onScrub = seek,
                onScrubEnd = {
                    player.setSeekParameters(SeekParameters.DEFAULT)
                    seek(v.progress.floatValue) // the exact frame, now the finger has stopped
                    v.scrubbing = false
                    if (v.resume) play()
                },
                onSkip = { side -> skip(side) },
                onSpeed = { speed ->
                    CrashLog.note("video speed $speed")
                    v.speed = speed
                    note = Note("${speedLabel(speed)} speed", Sym.Speed, Alignment.Center)
                },
                onSound = {
                    if (!v.hasSound) note = Note("This video has no sound", Sym.VolumeOff, Alignment.Center) // the button stays, as the design has it
                    else {
                        note = if (muted) Note("Sound on", Sym.VolumeUp, Alignment.Center) else Note("Muted", Sym.VolumeOff, Alignment.Center)
                        onSound()
                    }
                },
            )
        }
        if (v.held) {
            VideoNote(
                holdNote,
                Modifier.align(holdNote.place).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = Space.bar),
            )
        }
        note?.let { n ->
            // A skip's note: its top at half height, its outer edge 10% of the width in from its side
            val side = when (n.place) {
                Alignment.TopStart -> Modifier.offset(x = maxWidth / 10, y = maxHeight / 2)
                Alignment.TopEnd -> Modifier.offset(x = -maxWidth / 10, y = maxHeight / 2)
                else -> Modifier
            }
            key(n) { VideoNote(n, Modifier.align(n.place).then(side)) }
        }
    }
}

/** The video bar's show and hide curve, as the design has it. */
private val barEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

/**
 * The video bar, as Appearance's [controls] says. Clean: a glass row with play or pause, the time so far, the [Track],
 * the length, the speed button and sound ([onSound]; shown off for a silent video). Filmstrip: the [Filmstrip] panel,
 * with [strip]'s frames. Either way the speed button opens the speeds and Loop above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoBar(
    v: VideoState,
    controls: VideoControls,
    muted: Boolean,
    peek: ImageBitmap?,
    strip: List<ImageBitmap?>,
    onPlay: () -> Unit,
    onScrubStart: (Float) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onSkip: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onSound: () -> Unit,
) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val still = AlbumTheme.spec.still
    val second by remember(v) { derivedStateOf { v.at / 1000 } } // the time label changes once a second, not every frame
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        // As wide as the bar, the chips centred in it; it closes at once
        AnimatedVisibility(v.speedOpen, Modifier.fillMaxWidth(), enter = popUp(still, 200, speedsEase), exit = ExitTransition.None) {
            FlowRow(
                Modifier.fillMaxWidth().floating(RoundedCornerShape(Space.radiusLg)).pointerInput(Unit) {}.padding(Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                speeds.forEach { speed -> Chip(speedLabel(speed), { onSpeed(speed) }, selected = v.speed == speed) }
                Chip("Loop", {
                    CrashLog.note("video loop ${!v.loop}")
                    v.loop = !v.loop
                }, selected = v.loop, icon = Sym.Repeat)
            }
        }
        if (controls == VideoControls.Filmstrip) {
            Filmstrip(v, second, muted, strip, onPlay, onScrubStart, onScrub, onScrubEnd, onSkip, onSound)
            return@Column
        }
        // The glass sits behind the row rather than on it, so the peek can rise above the row without being cut off
        Box(Modifier.fillMaxWidth().height(barHeight)) {
            Box(Modifier.matchParentSize().floating())
            Row(
                // Touches between the buttons stay on the bar, rather than reaching the video under it
                Modifier.matchParentSize().pointerInput(Unit) {}.padding(start = 2.dp, end = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumIconButton(if (v.playing) Sym.Pause else Sym.PlayArrow, if (v.playing) "Pause" else "Play", onPlay)
                val time = Modifier.widthIn(min = 36.dp)
                Text(duration(second * 1000), time, style = type.meta, color = c.ink, textAlign = TextAlign.Center, maxLines = 1)
                Track(v, second, peek, onScrubStart, onScrub, onScrubEnd, Modifier.weight(1f))
                Text(duration(v.length), time, style = type.meta, color = c.ink, textAlign = TextAlign.Center, maxLines = 1)
                SpeedButton(v.speed, v.speedOpen) { v.speedOpen = !v.speedOpen }
                val off = muted || !v.hasSound
                AlbumIconButton(
                    if (off) Sym.VolumeOff else Sym.VolumeUp,
                    "Sound",
                    onSound,
                    Modifier.semantics { stateDescription = if (off) "Off" else "On" },
                )
            }
        }
    }
}

/**
 * The design's "filmstrip" controls: a glass panel with the time over its length, a strip of the video's frames under a
 * fixed flash playhead (drag it to scrub: the frames slide, left for later), and a row of back 10 s, a big play or
 * pause, forward 10 s ([onSkip] with -1 or 1), the speed button and sound. [strip] fills in as frames are decoded.
 */
@Composable
private fun Filmstrip(
    v: VideoState,
    second: Long,
    muted: Boolean,
    strip: List<ImageBitmap?>,
    onPlay: () -> Unit,
    onStart: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onEnd: () -> Unit,
    onSkip: (Int) -> Unit,
    onSound: () -> Unit,
) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    Column(
        Modifier.fillMaxWidth().floating(RoundedCornerShape(Space.radiusLg)).pointerInput(Unit) {}
            .padding(start = Space.s3, top = Space.s3, end = Space.s3, bottom = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s1, Alignment.CenterHorizontally)) {
            Text(duration(second * 1000), style = type.meta, color = c.ink, maxLines = 1)
            Text("/", style = type.meta, color = c.ink, maxLines = 1)
            Text(duration(v.length), style = type.meta, color = c.ink, maxLines = 1)
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(stripHeight).clip(RoundedCornerShape(Space.radiusMd))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume() // so the page neither turns nor closes meanwhile
                        val from = v.progress.floatValue
                        start(from)
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                move(stripFraction(from, change.position.x - down.position.x, stripLength.toPx()))
                            }
                        } finally {
                            end()
                        }
                    }
                }
                .positionSemantics(v, second, onMove),
        ) {
            val half = constraints.maxWidth / 2f
            // The video's start sits under the playhead at 0; read only while drawing, so playback never recomposes
            Row(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = half - v.progress.floatValue * stripLength.toPx() },
                horizontalArrangement = Arrangement.spacedBy(stripGap),
            ) {
                repeat(STRIP_FRAMES) { k ->
                    Box(Modifier.size(stripFrame, stripHeight).clip(RoundedCornerShape(4.dp)).background(c.void)) {
                        strip.getOrNull(k)?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
            // The playhead: 3dp of flash, ringed in on-flash
            Spacer(
                Modifier.matchParentSize().drawBehind {
                    val w = 3.dp.toPx()
                    val ring = 1.dp.toPx()
                    val x = size.width / 2
                    drawRoundRect(c.onFlash, Offset(x - w / 2 - ring, 0f), Size(w + ring * 2, size.height), CornerRadius(w / 2 + ring))
                    drawRoundRect(c.flash, Offset(x - w / 2, 0f), Size(w, size.height), CornerRadius(w / 2))
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
            AlbumIconButton(Sym.Replay10, "Back 10 seconds", { onSkip(-1) })
            PlayBig(v.playing, onPlay)
            AlbumIconButton(Sym.Forward10, "Forward 10 seconds", { onSkip(1) })
            Spacer(Modifier.weight(1f))
            SpeedButton(v.speed, v.speedOpen) { v.speedOpen = !v.speedOpen }
            val off = muted || !v.hasSound
            AlbumIconButton(
                if (off) Sym.VolumeOff else Sym.VolumeUp,
                "Sound",
                onSound,
                Modifier.semantics { stateDescription = if (off) "Off" else "On" },
            )
        }
    }
}

/** The filmstrip's play or pause: a 52dp flash disc that shrinks while pressed, with a slight overshoot back. */
@Composable
private fun PlayBig(playing: Boolean, onClick: () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !still) 0.9f else 1f, if (still) snap() else tween(160, easing = playEase), label = "playPress")
    Box(
        Modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(c.flash, CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) { Icon(if (playing) Sym.Pause else Sym.PlayArrow, null, filled = true, tint = c.onFlash) }
}

/** The big play button's press curve, as the design has it. */
private val playEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.4f)

/** TalkBack hears a scrubber as a slider, [second] of the video's length; setting it moves there through [onMove]. */
private fun Modifier.positionSemantics(v: VideoState, second: Long, onMove: (Float) -> Unit) = semantics {
    val total = (v.length / 1000).coerceAtLeast(1)
    contentDescription = "Video position"
    stateDescription = duration(second * 1000)
    progressBarRangeInfo = ProgressBarRangeInfo(second.toFloat(), 0f..total.toFloat())
    setProgress { target ->
        onMove((target / total).coerceIn(0f, 1f))
        true
    }
}

/**
 * The bar's track: a line filled to where the video is, and a thumb. Press anywhere on it and drag to scrub: the thumb
 * grows, the line thickens, and the [peek] above the thumb shows the frame under the finger with its time. TalkBack
 * hears it as a slider, [second] of the video's length.
 */
@Composable
private fun Track(
    v: VideoState,
    second: Long,
    peek: ImageBitmap?,
    onStart: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val grow by animateFloatAsState(
        if (v.scrubbing) 1f else 0f,
        if (still) snap() else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "thumb",
    )
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier
            .height(Space.target)
            .padding(horizontal = Space.s1)
            .pointerInput(Unit) {
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume() // so the page neither turns nor closes meanwhile
                    start(at(down.position.x))
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            move(at(change.position.x))
                        }
                    } finally {
                        end()
                    }
                }
            }
            .positionSemantics(v, second, onMove)
            .drawBehind {
                val line = (4 + 2 * grow).dp.toPx()
                val top = size.height / 2 - line / 2
                val x = size.width * v.progress.floatValue
                drawRoundRect(c.lineStrong.copy(alpha = 0.5f), Offset(0f, top), Size(size.width, line), CornerRadius(line / 2))
                drawRoundRect(c.ink, Offset(0f, top), Size(x, line), CornerRadius(line / 2))
                drawCircle(c.ink, 7.dp.toPx() * (1f + 0.45f * grow), Offset(x, size.height / 2))
            },
    ) {
        AnimatedVisibility(
            v.scrubbing,
            // Centred over the thumb, its bottom level with the top of the glass row: outside the track, at its own size
            Modifier.layout { measurable, constraints ->
                val peek = measurable.measure(Constraints())
                layout(0, 0) {
                    val x = constraints.maxWidth * v.progress.floatValue - peek.width / 2f
                    // its white ring sits outside the frame, so the frame's bottom stays level with the row's top
                    peek.place(x.roundToInt(), (Space.target.toPx() / 2 - barHeight.toPx() / 2 + peekRing.toPx()).roundToInt() - peek.height)
                }
            },
            enter = popUp(still),
            exit = ExitTransition.None,
        ) { Peek(peek, duration(second * 1000)) }
    }
}

/** The frame under the finger while scrubbing (black until one is decoded), ringed in white outside it, with its time at the bottom. */
@Composable
private fun Peek(frame: ImageBitmap?, time: String) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusMd)
    val ring = RoundedCornerShape(Space.radiusMd + peekRing)
    Box(
        Modifier.size(peekWidth + peekRing * 2, peekHeight + peekRing * 2).floating(ring).background(c.onPhoto, ring)
            .padding(peekRing).clip(shape).background(c.void),
        contentAlignment = Alignment.BottomCenter,
    ) {
        frame?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        Text(
            time,
            Modifier.padding(bottom = Space.s1).background(c.scrimPhoto, CircleShape).padding(horizontal = 6.dp),
            style = AlbumTheme.type.meta,
            color = c.onPhoto,
        )
    }
}

/** The speed button, "1×": opens the speeds above the bar, and turns flash while they're open. It shrinks while pressed. */
@Composable
private fun SpeedButton(speed: Float, open: Boolean, onClick: () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !still) 0.92f else 1f, if (still) snap() else tween(160), label = "speedPress")
    val fill by animateColorAsState(if (open) c.flash else c.surfaceHigh, if (still) snap() else tween(160), label = "speedFill")
    Box(
        Modifier.heightIn(min = Space.target).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(source, indication = null, role = Role.Button, onClickLabel = if (open) "Hide speeds" else "Show speeds", onClick = onClick)
            .semantics {
                contentDescription = "Playback speed"
                stateDescription = speedLabel(speed)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(34.dp).widthIn(min = 46.dp).background(fill, CircleShape).padding(horizontal = Space.s2),
            contentAlignment = Alignment.Center,
        ) { Text(speedLabel(speed), style = AlbumTheme.type.label, color = if (open) c.onFlash else c.ink, maxLines = 1) }
    }
}

/** A note over the video for what was just done ("+10 s", "Muted"): a dark pill that pops up. TalkBack reads it out. */
@Composable
private fun VideoNote(note: Note, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val pop = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(160)) }
    Row(
        modifier
            .graphicsLayer {
                alpha = pop.value
                scaleX = 0.9f + 0.1f * pop.value
                scaleY = scaleX
                translationY = (1f - pop.value) * 8.dp.toPx()
            }
            .background(c.scrimPhoto, CircleShape)
            .padding(horizontal = Space.s4, vertical = Space.s2)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(note.icon, null, if (note.flip) Modifier.graphicsLayer { scaleX = -1f } else Modifier, filled = true, size = Space.iconSm, tint = c.onPhoto)
        Text(note.text, style = AlbumTheme.type.label, color = c.onPhoto)
    }
}

/** The design's pop-up: from a little lower and smaller, [ms] long; nothing moves with Remove animations. */
private fun popUp(still: Boolean, ms: Int = 160, easing: Easing = FastOutSlowInEasing): EnterTransition =
    if (still) EnterTransition.None else fadeIn(tween(ms, easing = easing)) + scaleIn(tween(ms, easing = easing), initialScale = 0.9f) + slideInVertically(tween(ms, easing = easing)) { it / 8 }

/** The speeds' pop-up, with a slight overshoot. */
private val speedsEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)

/** The scrub peek's white ring, outside its frame. */
private val peekRing = 2.dp

/**
 * Press and hold: [onHold] gets true once the finger has stayed put for a long press, and false when it lifts. It watches
 * touches before the zoom does and takes none until the hold starts; from then on it takes them all, so a hold never
 * pans, turns the page or closes the viewer. Moving first, or a second finger (a pinch), is no hold. Restarts with [key].
 */
private fun Modifier.holdGesture(key: Any, onHold: (Boolean) -> Unit) = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.size > 1 || change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (ended != null) return@awaitEachGesture // a tap, a drag or a pinch
        onHold(true)
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        } finally {
            onHold(false)
        }
    }
}

/**
 * A small frame of the video at [uri] near [at] (ms) while [on], for the scrub peek; null until one is decoded. One
 * retriever while scrubbing, released after. Frames come from the nearest key frame, which is quick enough to follow a
 * finger; the player shows the exact frame once it's let go.
 */
@Composable
private fun rememberPeek(uri: Uri, on: Boolean, at: () -> Long): ImageBitmap? {
    val context = LocalContext.current
    val side = with(LocalDensity.current) { peekWidth.roundToPx() * 2 } // room to crop a tall video into the wide peek
    var frame by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, on) {
        if (!on) return@LaunchedEffect
        frame = null // not the last scrub's frame
        withFrames(context, uri, "peek") { retriever ->
            // A newer position cancels the wait for an older one; each decode still finishes before the next starts
            snapshotFlow { at() / PEEK_STEP_MS }.collectLatest { step ->
                withContext(Dispatchers.IO) { retriever.smallFrame(step * PEEK_STEP_MS, side) }?.let { frame = it }
            }
        }
    }
    return frame
}

/** The last few videos' filmstrips, so coming back to one shows it at once. Touched only on the main thread. */
private val strips = object : LinkedHashMap<Uri, List<ImageBitmap?>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, List<ImageBitmap?>>) = size > 4 // ponytail: about 1 MB each
}

/**
 * The [STRIP_FRAMES] small frames of the video at [uri] for the filmstrip while [on], decoded one by one off the main
 * thread, so the strip fills in from its start; null where one isn't decoded (yet). Kept in [strips] once all are done.
 */
@Composable
private fun rememberStrip(uri: Uri, on: Boolean): List<ImageBitmap?> {
    val context = LocalContext.current
    val side = with(LocalDensity.current) { stripDecode.roundToPx() }
    val frames = remember(uri) { mutableStateListOf<ImageBitmap?>().apply { strips[uri]?.let { addAll(it) } } }
    LaunchedEffect(uri, on) {
        if (!on || frames.size == STRIP_FRAMES) return@LaunchedEffect
        frames.clear() // a strip left half done
        withFrames(context, uri, "strip") { retriever ->
            val length = withContext(Dispatchers.IO) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } ?: return@withFrames
            repeat(STRIP_FRAMES) { k -> frames += withContext(Dispatchers.IO) { retriever.smallFrame(stripFrameAt(k, length), side) } }
            strips[uri] = frames.toList()
        }
    }
    return frames
}

/** Opens a retriever on the video at [uri] for [use], off the main thread, and releases it after; notes a failure to open as [what]. */
private suspend fun withFrames(context: Context, uri: Uri, what: String, use: suspend (MediaMetadataRetriever) -> Unit) {
    val retriever = MediaMetadataRetriever()
    try {
        val opened = withContext(Dispatchers.IO) {
            runCatching { retriever.setDataSource(context, uri) }.onFailure { CrashLog.note("$what failed: $it") }.isSuccess
        }
        if (opened) use(retriever)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { retriever.release() }
    }
}

/** A frame near [ms] from the nearest key frame, which is quick, scaled to fit a [side] px square; null if it can't be decoded. */
private fun MediaMetadataRetriever.smallFrame(ms: Long, side: Int): ImageBitmap? =
    runCatching { getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side) }.getOrNull()?.asImageBitmap()
