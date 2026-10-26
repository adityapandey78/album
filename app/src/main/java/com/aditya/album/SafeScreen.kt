package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import java.time.Instant
import java.time.ZoneId
import androidx.media3.datasource.DataSource as MediaDataSource

/** What the lock screen says once Android has erased the key of a safe with no privacy PIN: see [Safe.reset]. */
private const val LOST_NOTE =
    "Android erased this safe's key when the screen lock was turned off, so what's inside can't be opened any more."

/** What the first move into the safe says: nothing in it has a second copy. */
private const val SAFE_NOTE =
    "They're encrypted on this phone and open with your privacy PIN or fingerprint. The safe isn't backed up, so uninstalling " +
        "Album or clearing its storage erases what's inside."

/**
 * Moves photos and videos into the safe, asking once first: a ConfirmBar with the photos while privacy is unlocked (with
 * the safe's note on its first use), else the privacy PIN or fingerprint in a sheet that names the move (setting up a PIN
 * first if there's none). Android's pop-up then deletes the originals, once every copy is verified; the result shows in
 * the snackbar: "Moved 12 items to Private safe". Get one with [rememberMoveToSafe].
 */
@Stable
class MoveToSafe internal constructor(private val start: (List<Media>, Boolean) -> Unit) {
    /** [confirmed]: the caller already asked with its own ConfirmBar (For you's ID cards), so only the PIN may be asked for. */
    operator fun invoke(items: List<Media>, confirmed: Boolean = false) = start(items, confirmed)
}

/**
 * Returns the [MoveToSafe] of this screen. [onStart] runs once the move begins (the selection it came from is done), so
 * a cancelled PIN leaves the selection as it was. Draws its own ConfirmBar, PIN sheet and progress card (windows of their
 * own, so it works wherever it's called) and also finishes a move the app was closed in the middle of.
 */
@Composable
fun rememberMoveToSafe(snackbar: SnackbarHostState, onStart: () -> Unit = {}): MoveToSafe {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val privacy = rememberPrivacyAsk()
    val started by rememberUpdatedState(onStart)
    var confirming by remember { mutableStateOf<List<Media>?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        Safe.answer(it.resultCode == Activity.RESULT_OK)
    }
    val ask = Safe.asking.collectAsState().value
    LaunchedEffect(ask) {
        if (ask != null && !ask.shown) {
            ask.shown = true // a screen recreated while Android's pop-up is open must not show it twice
            request.launch(IntentSenderRequest.Builder(ask.request).build())
        }
    }
    LaunchedEffect(snackbar) { for (message in Safe.libraryMessages) snackbar.showSnackbar(message) }
    Safe.progress.collectAsState().value?.takeIf { !it.inSafe }?.let { SafeProgress(it) }

    // The move holds its own copy of the key, so privacy locking mid-move (Android's pop-up, leaving Album) doesn't stop it
    fun move(items: List<Media>) = privacy.run("Move ${itemCount(items.size)} to Private safe") {
        val key = Safe.key.value?.copyOf() ?: return@run
        CrashLog.note("safe move in ${items.size}")
        started()
        Safe.moveIn(context.applicationContext, items, key)
    }
    confirming?.let { items ->
        // The safe's note on its first use only: whether it has anything yet, read once off the main thread
        val empty by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { Safe.isEmpty() } }
        empty?.let {
            ConfirmBar(
                title = "Move ${itemCount(items.size)} to Private safe?",
                body = "They leave the Library and search." + if (it) " $SAFE_NOTE" else "",
                confirmLabel = "Move to safe",
                onConfirm = {
                    confirming = null
                    move(items)
                },
                onCancel = { confirming = null },
                icon = Sym.Lock,
                tone = ConfirmTone.Flash,
                thumbs = items.take(3),
            )
        }
    }
    return remember {
        MoveToSafe { items, confirmed ->
            when {
                !context.canSend(items.size, MAX_REQUEST, "move") -> {}
                // Without it Android hands over photos with their GPS blanked, and deleting the original would lose the place
                context.checkSelfPermission(ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                    haptics.reject()
                    scope.launch { snackbar.showSnackbar(SAFE_NEEDS_LOCATION) }
                }
                // Locked, the PIN sheet naming the move is the one deliberate step; unlocked, the ConfirmBar is
                confirmed || !PrivacyLock.isOpen -> move(items)
                else -> confirming = items
            }
        }
    }
}

/**
 * Private safe, a pushed page. Locked: the privacy PIN pad (or the fingerprint). Open: decrypted thumbnails, long-press to
 * select, then Move out or Delete forever; tap to view. Screenshots and the recents preview are blocked while it's on
 * screen (FLAG_SECURE), and privacy locks when you leave the page, when Album leaves the screen, and on Lock now.
 */
@Composable
fun SafeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    val key by Safe.key.collectAsState()
    PrivatePage(lockOnLeave = true)
    Box(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).pointerInput(Unit) {}) { // catches taps meant for the screen underneath
        when (val k = key) {
            null -> LockedSafe(onBack)
            else -> OpenSafe(k, onBack)
        }
    }
    Safe.progress.collectAsState().value?.takeIf { it.inSafe }?.let { SafeProgress(it) }
}

/**
 * The safe while locked: the privacy PIN pad. A v1.0 safe with no PIN whose key Android erased can't be opened by
 * anything: then it says so, and offers Reset safe instead.
 */
@Composable
private fun LockedSafe(onBack: () -> Unit) {
    val wrapLost by Safe.wrapLost.collectAsState()
    val pin by PrivacyLock.setUp.collectAsState()
    LaunchedEffect(Unit) { Safe.checkKey() }
    if (!wrapLost || pin) {
        PrivacyGate("Private safe", Sym.Lock, "Encrypted on this phone and hidden from the Library, search and other apps.", onBack)
        return
    }
    val c = AlbumTheme.colors
    var resetting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.statusBarsPadding()) { CollapsedTopBar("Private safe", onBack = onBack) }
        Column(
            Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().padding(start = Space.s8, end = Space.s8, bottom = Space.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s3, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(88.dp).background(c.flashSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Sym.Lock, null, size = 44.dp, tint = c.flashInk)
            }
            Text("Private safe", style = AlbumTheme.type.titleLg, color = c.ink)
            Text(LOST_NOTE, style = AlbumTheme.type.body, color = c.inkMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.s2))
            AlbumButton("Reset safe", { resetting = true }, variant = ButtonVariant.Danger, icon = Sym.DeleteForever)
        }
    }
    if (resetting) {
        val count = Safe.items.collectAsState(null).value?.size
        ConfirmBar(
            title = "Reset Private safe?",
            body = (count?.let { "Its ${itemCount(it)} can't be opened any more, by Album or anyone else. " } ?: "") +
                "Resetting erases them from this phone and starts an empty safe. This cannot be undone.",
            confirmLabel = "Hold to reset",
            onConfirm = {
                resetting = false
                Safe.reset()
            },
            onCancel = { resetting = false },
            icon = Sym.DeleteForever,
            hold = true,
        )
    }
}

// A few thumbnails decrypt at a time, like the grid's (see Media.kt), so a fling doesn't starve the UI threads
private val safeLoads = Dispatchers.IO.limitedParallelism(4)

@Composable
private fun OpenSafe(key: ByteArray, onBack: () -> Unit) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val c = spec.colors
    val haptics = rememberHaptics()
    val items by Safe.items.collectAsState(null)
    val list = items.orEmpty()
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    var toDelete by remember { mutableStateOf<List<SafeItem>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) { for (message in Safe.safeMessages) snackbar.showSnackbar(message) }
    val chosen = list.filter { it.id in selected } // ignores items that left the safe meanwhile
    val selecting = chosen.isNotEmpty()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler { if (selecting) selected = emptySet() else onBack() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(c.canvas).statusBarsPadding()) {
                CollapsedTopBar("Private safe", subtitle = "${itemCount(list.size)} · locks when you leave", onBack = onBack) {
                    AlbumIconButton(Sym.Lock, "Lock now", {
                        CrashLog.note("safe lock now")
                        PrivacyLock.lock()
                    })
                }
            }
            when {
                items == null -> {} // first load takes a few ms
                list.isEmpty() -> EmptyState(
                    Sym.Lock, "Nothing in the safe",
                    "Photos you move here leave the Library and search, and open only with your privacy PIN or fingerprint.",
                )
                else -> LazyVerticalGrid(
                    GridCells.Fixed(DEFAULT_COLUMNS),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = Space.s3, bottom = bottom + if (selecting) SelectionClearance else Space.s8),
                    horizontalArrangement = Arrangement.spacedBy(spec.gutter),
                    verticalArrangement = Arrangement.spacedBy(spec.gutter),
                ) {
                    items(list, key = { it.id }) { item ->
                        val isSelected = item.id in selected
                        SafeTile(
                            item, key, selecting, isSelected,
                            Modifier.aspectRatio(1f).combinedClickable(
                                onLongClickLabel = "Select",
                                onLongClick = {
                                    haptics.longPress()
                                    selected = selected + item.id
                                },
                                hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
                            ) {
                                when {
                                    selecting -> selected = if (isSelected) selected - item.id else selected + item.id
                                    else -> viewing = item.id
                                }
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = if (spec.still) fadeIn(tween(0)) else slideInVertically { it } + fadeIn(),
            exit = if (spec.still) fadeOut(tween(0)) else slideOutVertically { it } + fadeOut(),
        ) {
            SelectionBar(
                chosen.size,
                listOf(
                    SelAction(Sym.LockOpen, "Move out") {
                        CrashLog.note("safe move out ${chosen.size}")
                        Safe.moveOut(context.applicationContext, chosen, key.copyOf()) // the job wipes its copy when done
                        selected = emptySet()
                    },
                    SelAction(Sym.DeleteForever, "Delete forever", danger = true) { toDelete = chosen },
                ),
                onClose = { selected = emptySet() },
                onSelectAll = if (chosen.size < list.size) ({ selected = list.mapTo(HashSet()) { it.id } }) else null,
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (selecting) SelectionBarHeight + Space.s2 else 0.dp),
        ) { AlbumSnackbar(it) }

        viewing?.let { id ->
            val start = list.indexOfFirst { it.id == id }
            if (start >= 0) SafeViewer(list, start, key, onClose = { viewing = null }) // else moved out or deleted meanwhile
        }
    }

    toDelete?.let { doomed ->
        ConfirmBar(
            title = "Delete ${itemCount(doomed.size)} forever?",
            body = "${if (doomed.size == 1) "It" else "They"} will be erased from this phone. This cannot be undone.",
            confirmLabel = "Hold to delete",
            onConfirm = {
                CrashLog.note("safe delete forever ${doomed.size}")
                toDelete = null
                selected = emptySet()
                Safe.delete(doomed)
            },
            onCancel = { toDelete = null },
            icon = Sym.DeleteForever,
            hold = true,
        )
    }
}

/** Just what [tileLabel] reads. A safe item has no MediaStore uri. */
private fun SafeItem.asMedia() = Media(
    id, Uri.EMPTY, isVideo, taken, Instant.ofEpochMilli(taken).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(),
    width, height, durationMs, 0, "", favorite, size, name, path,
)

/** A safe item in the grid, drawn like [MediaTile] but from its thumbnail decrypted into memory, never Coil's caches. */
@Composable
private fun SafeTile(item: SafeItem, key: ByteArray, selecting: Boolean, selected: Boolean, modifier: Modifier) {
    val spec = AlbumTheme.spec
    val c = spec.colors
    val thumb by produceState<ImageBitmap?>(null, item.id) { value = withContext(safeLoads) { Safe.thumbnail(item, key) } }
    val scale by animateFloatAsState(
        if (selected) 0.84f else 1f,
        if (spec.still) snap() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )
    val corner by animateDpAsState(if (selected) Space.radiusMd else spec.tileCorner, if (spec.still) snap() else spring(), label = "tileCorner")
    val media = remember(item) { item.asMedia() }
    Box(modifier.semantics { contentDescription = tileLabel(media, selected); if (selecting) this.selected = selected }) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(corner))
                .background(c.surfaceHigh),
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (item.isVideo) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to c.scrimPhoto))
                        .padding(start = Space.s1, end = Space.s1, top = Space.s4, bottom = Space.s1),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.PlayArrow, null, filled = true, size = 16.dp, tint = c.onPhoto)
                    Text(duration(item.durationMs), style = AlbumTheme.type.meta, color = c.onPhoto, maxLines = 1)
                }
            }
        }
        if (selecting) SelectCheck(selected, Modifier.align(Alignment.TopStart).padding(Space.check / 3))
    }
}

/** Full screen over the grid: swipe between items, pinch to zoom photos, tap for the back button (and video controls). */
@Composable
private fun SafeViewer(items: List<SafeItem>, start: Int, key: ByteArray, onClose: () -> Unit) {
    val c = AlbumTheme.colors
    val pager = rememberPagerState(start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    BackHandler(onBack = onClose)
    ImmersiveBars(chrome)
    Box(Modifier.fillMaxSize().background(c.void).pointerInput(Unit) {}) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items[it].id }) { page ->
            val item = items[page]
            if (item.isVideo) SafeVideo(item, key, active = pager.settledPage == page, chrome = chrome, onTap = { chrome = !chrome })
            else SafePhoto(item, key, onTap = { chrome = !chrome })
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Space.s2), enter = fadeIn(), exit = fadeOut()) {
            AlbumIconButton(Sym.ArrowBack, "Back", onClose, variant = IconButtonVariant.Photo)
        }
    }
}

@Composable
private fun SafePhoto(item: SafeItem, key: ByteArray, onTap: () -> Unit) {
    val c = AlbumTheme.colors
    val thumb by produceState<ImageBitmap?>(null, item.id) { value = withContext(safeLoads) { Safe.thumbnail(item, key) } }
    var failed by remember(item.id) { mutableStateOf(false) }
    val full by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { Safe.photo(item, key) }.onFailure {
                CrashLog.note("safe photo failed: ${it.javaClass.simpleName}")
                failed = true
            }.getOrNull()
        }
    }
    val zoom = rememberZoomableState()
    Box(Modifier.fillMaxSize()) {
        // The thumbnail shows until the full photo is decrypted, then the photo takes its place at the same size
        (full ?: thumb)?.let { image ->
            LaunchedEffect(image) {
                zoom.setContentLocation(ZoomableContentLocation.scaledToFitAndCenterAligned(Size(image.width.toFloat(), image.height.toFloat())))
            }
            Image(image, item.name, Modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() }), contentScale = ContentScale.Fit)
        }
        if (failed) Text("Can't open this photo", Modifier.align(Alignment.Center), style = AlbumTheme.type.body, color = c.onPhoto)
    }
}

@androidx.annotation.OptIn(UnstableApi::class) // Media3 marks custom data sources unstable; checked by lint, not the compiler
@Composable
private fun SafeVideo(item: SafeItem, key: ByteArray, active: Boolean, chrome: Boolean, onTap: () -> Unit) {
    if (!active) {
        val thumb by produceState<ImageBitmap?>(null, item.id) { value = withContext(safeLoads) { Safe.thumbnail(item, key) } }
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        return
    }
    val context = LocalContext.current
    val c = AlbumTheme.colors
    var playing by remember(item.id) { mutableStateOf(true) } // wants to play: paused and finished are both false
    var failed by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) {
        // Straight from the encrypted file, a chunk at a time: no plain copy anywhere, and no player cache
        val source = ProgressiveMediaSource.Factory(MediaDataSource.Factory { SafeDataSource { Safe.reader(item, key) } })
            .createMediaSource(MediaItem.fromUri("safe:${item.id}"))
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                }

                override fun onPlayerError(error: PlaybackException) {
                    CrashLog.note("safe video error ${error.errorCodeName}")
                    failed = true
                }
            })
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Leaving the app locks the safe, but a stopped activity doesn't recompose, so this page isn't disposed until you
    // come back: stop now, so nothing keeps playing (or decoding) in the background
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.stop() }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    // FLAG_SECURE covers the window, but a SurfaceView is its own layer and needs its own flag
                    (videoSurfaceView as? SurfaceView)?.setSecure(true)
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) // above the player view, which would take the touches
        if (failed) {
            Text("Can't play this video", Modifier.align(Alignment.Center), style = AlbumTheme.type.body, color = c.onPhoto)
        } else if (chrome || !playing) {
            AlbumIconButton(
                if (playing) Sym.Pause else Sym.PlayArrow,
                if (playing) "Pause" else "Play",
                {
                    when {
                        playing -> player.pause()
                        player.playbackState == Player.STATE_ENDED -> {
                            player.seekTo(0)
                            player.play()
                        }
                        else -> player.play()
                    }
                },
                Modifier.align(Alignment.Center),
                variant = IconButtonVariant.Photo,
            )
        }
    }
}

/** Feeds the player a video from its encrypted file: each read decrypts only the 64 KB chunk it needs, in memory. */
@androidx.annotation.OptIn(UnstableApi::class) // Media3 marks custom data sources unstable; checked by lint, not the compiler
private class SafeDataSource(private val openReader: () -> SafeReader) : BaseDataSource(false) {
    private var reader: SafeReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var left = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = openReader().also { reader = it }
        if (dataSpec.position > r.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) r.size - position else minOf(dataSpec.length, r.size - position)
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = checkNotNull(reader).read(position, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri() = uri

    override fun close() {
        uri = null
        reader?.let {
            reader = null
            it.close()
            transferEnded()
        }
    }
}

/**
 * A move in or out at work, as a bottom card like AlbumOps' progress card: "Moving 3 of 40" and Stop. It can't be swiped
 * or backed away, because only Stop ends the job cleanly. Quick moves finish before it appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafeProgress(p: Safe.Progress) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        visible = true
    }
    if (!visible) return
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusLg)
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden }),
        sheetMaxWidth = 640.dp,
        sheetGesturesEnabled = false,
        containerColor = Color.Transparent,
        contentColor = c.ink,
        scrimColor = c.scrim,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) }, // the card pads for the navigation bar itself
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false, shouldDismissOnClickOutside = false),
    ) {
        Column(
            Modifier.padding(horizontal = Space.s3).padding(bottom = Space.s4).navigationBarsPadding()
                .floating(shape).background(c.surface, shape).padding(Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text("Moving ${"%,d".format(p.current)} of ${"%,d".format(p.total)}", style = AlbumTheme.type.titleLg, color = c.ink)
            Text("Each item is checked before its old copy is removed, so stopping loses nothing.", style = AlbumTheme.type.body, color = c.inkMuted)
            ProgressLine((p.current - 1f) / p.total)
            AlbumButton("Stop", Safe::stop, Modifier.fillMaxWidth().heightIn(min = Space.target + Space.s2))
        }
    }
}
