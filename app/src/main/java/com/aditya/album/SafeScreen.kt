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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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

/**
 * Moves photos and videos into the safe, asking once first: a ConfirmBar with the photos (with the only-copy line on its
 * first use). No PIN: the move is sealed to the safe's public key and privacy stays locked (a safe not opened since it got
 * that key asks for the PIN once, see [PrivacyAsk.forMoveIn]). Android's pop-up then deletes the originals, once every copy
 * is verified; the result shows in the snackbar: "Moved 12 items to Private safe". Get one with [rememberMoveToSafe].
 */
@Stable
class MoveToSafe internal constructor(private val start: (MoveRequest, Boolean) -> Unit) {
    /**
     * [confirmed]: the caller already asked on its own page (For you's ID photos), so nothing more is asked. [note]: a
     * line the ConfirmBar adds. [onStart] runs once the move begins.
     */
    operator fun invoke(items: List<Media>, confirmed: Boolean = false, note: String? = null, onStart: () -> Unit = {}) =
        start(MoveRequest(items, note, onStart), confirmed)
}

/** A move into the safe waiting for its ConfirmBar: see [MoveToSafe]. */
class MoveRequest(val items: List<Media>, val note: String?, val onStart: () -> Unit)

/**
 * Returns the [MoveToSafe] of this screen. [onStart] runs once the move begins (the selection it came from is done), so
 * a cancelled ConfirmBar (or the one-time PIN) leaves the selection as it was. Draws its own ConfirmBar, PIN sheet and
 * progress card (windows of their own, so it works wherever it's called) and also finishes a move the app was closed in
 * the middle of.
 */
@Composable
fun rememberMoveToSafe(snackbar: SnackbarHostState, onStart: () -> Unit = {}): MoveToSafe {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val privacy = rememberPrivacyAsk()
    val started by rememberUpdatedState(onStart)
    var confirming by remember { mutableStateOf<MoveRequest?>(null) }
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

    // Sealed, the move needs no key and never unlocks privacy; with a key, it holds its own copy, which it wipes
    fun move(request: MoveRequest) = privacy.forMoveIn("Move ${itemCount(request.items.size)} to Private safe") { key ->
        CrashLog.note("safe move in ${request.items.size}${if (key == null) " sealed" else ""}")
        started()
        request.onStart()
        Safe.moveIn(context.applicationContext, request.items, key)
    }
    confirming?.let { request ->
        val items = request.items
        // The only-copy line on its first use: whether it has anything yet, read once off the main thread
        val empty by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { Safe.isEmpty() } }
        empty?.let {
            ConfirmBar(
                title = "Move ${these(items.size, items.first().isVideo)} to Private safe?",
                body = "${if (items.size == 1) "It leaves" else "They leave"} the Library and search, and open${if (items.size == 1) "s" else ""} only with your PIN or fingerprint." + (if (it) " ${onlyCopyLine(items.size)}" else "") +
                    request.note?.let { note -> " $note" }.orEmpty(),
                confirmLabel = "Move to safe",
                onConfirm = {
                    confirming = null
                    move(request)
                },
                onCancel = { confirming = null },
                icon = Sym.Lock,
                tone = ConfirmTone.Flash,
                thumbs = items.take(2),
            )
        }
    }
    return remember {
        MoveToSafe { request, confirmed ->
            when {
                !context.canSend(request.items.size, MAX_REQUEST, "move") -> {}
                // Without it Android hands over photos with their GPS blanked, and deleting the original would lose the place
                context.checkSelfPermission(ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                    haptics.reject()
                    scope.launch { snackbar.showSnackbar(SAFE_NEEDS_LOCATION) }
                }
                // The ConfirmBar is the one deliberate step, or the caller's own page was
                confirmed -> move(request)
                else -> confirming = request
            }
        }
    }
}

/**
 * Private safe, a pushed page. Locked: the privacy PIN pad (or the fingerprint key). Open: decrypted thumbnails under
 * their days, as many across as the Library has, long-press to select, then Move out (to where each came from or a
 * folder picked) or Delete forever; tap to view. Add picks more from the Library ([photos], [albums], [collections] and
 * People, all among [media]) for [moveToSafe]. Screenshots and the recents preview are blocked while it's on screen
 * (FLAG_SECURE), and privacy locks when you leave the page, when Album leaves the screen, and on Lock now.
 */
@Composable
fun SafeScreen(
    photos: List<Media>,
    media: List<Media>,
    albums: List<Album>,
    collections: List<CollectionAlbum>,
    moveToSafe: MoveToSafe,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    val key by Safe.key.collectAsState()
    PrivatePage()
    Box(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).pointerInput(Unit) {}) { // catches taps meant for the screen underneath
        when (val k = key) {
            null -> LockedSafe(onBack)
            else -> OpenSafe(k, albums, onBack) { onClose -> AddToSafePage(photos, media, albums, collections, moveToSafe, onClose) }
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
        PrivacyGate("Private safe", Sym.Lock, "The Private safe is locked.", onBack)
        return
    }
    var resetting by remember { mutableStateOf(false) }
    LockedState("Private safe", onBack, Sym.Error, "Private safe can't be opened", LOST_NOTE) {
        AlbumButton("Reset safe", { resetting = true }, Modifier.fillMaxWidth(), ButtonVariant.Danger, icon = Sym.DeleteForever)
    }
    if (resetting) {
        val count = Safe.items.collectAsState(null).value?.size
        ConfirmBar(
            title = "Reset Private safe?",
            body = (count?.takeIf { it > 0 }?.let { "Its ${itemCount(it)} ${if (it == 1) "is" else "are"} erased from this phone and can't be opened again. " } ?: "") +
                "Hidden people and albums stay hidden.",
            confirmLabel = "Hold to reset",
            onConfirm = {
                resetting = false
                Safe.reset()
            },
            onCancel = { resetting = false },
            icon = Sym.LockReset,
            hold = true,
        )
    }
}

/** The Library's column count's key (MainActivity), which the safe's grid follows. */
private const val LIBRARY_COLUMNS = "columns_photos"

// A few thumbnails decrypt at a time, like the grid's (see Media.kt), so a fling doesn't starve the UI threads
private val safeLoads = Dispatchers.IO.limitedParallelism(4)

/** The open safe. Move out offers [albums] as folders to go to; [addPage] draws the Add page over it until its onClose runs. */
@Composable
private fun OpenSafe(key: ByteArray, albums: List<Album>, onBack: () -> Unit, addPage: @Composable (onClose: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val c = spec.colors
    val items by Safe.items.collectAsState(null)
    val list = items.orEmpty()
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    var toDelete by remember { mutableStateOf<List<SafeItem>?>(null) }
    var toMoveOut by remember { mutableStateOf<List<SafeItem>?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
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
                    AlbumIconButton(Sym.Add, "Add to safe", { adding = true })
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
                ) {
                    AlbumButton("Add photos", { adding = true }, variant = ButtonVariant.Primary, icon = Sym.Add)
                }
                else -> {
                    OnlyCopyNote(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s2))
                    SafeGrid(list, key, chosen.mapTo(HashSet()) { it.id }, { selected = it }, { viewing = it }, bottom + if (selecting) SelectionClearance else Space.s8)
                }
            }
        }
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = dockRise(),
            exit = ExitTransition.None,
        ) {
            SelectionBar(
                chosen.size,
                listOf(
                    SelAction(Sym.LockOpen, "Move out") { toMoveOut = chosen },
                    SelAction(Sym.DeleteForever, "Delete forever", danger = true) { toDelete = chosen },
                ),
                onClose = { selected = emptySet() },
                onSelectAll = if (chosen.size < list.size) ({ selected = list.mapTo(HashSet()) { it.id } }) else null,
            )
        }
        AlbumSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (selecting) SelectionBarHeight + Space.s2 else 0.dp),
        )

        viewing?.let { id ->
            val start = list.indexOfFirst { it.id == id }
            if (start >= 0) SafeViewer(list, start, key, onClose = { viewing = null }) // else moved out or deleted meanwhile
        }
        if (adding) addPage { adding = false }
    }

    toMoveOut?.let { leaving ->
        MoveOutSheet(leaving, albums, onDismiss = { toMoveOut = null }) { into ->
            CrashLog.note("safe move out ${leaving.size} ${if (into == null) "back" else "to folder"}")
            toMoveOut = null
            selected = emptySet()
            // The job wipes its copy when done. A folder is looked up off the main thread first: the safe may lock meanwhile
            Safe.key.value?.let { Safe.moveOut(context.applicationContext, leaving, it.copyOf(), into) }
        }
    }

    toDelete?.let { doomed ->
        ConfirmBar(
            title = "Delete ${itemCount(doomed.size)} forever?",
            body = "${if (doomed.size == 1) "It'll" else "They'll"} be erased from this phone. This can't be undone.",
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
            fromBar = true,
        )
    }
}

/**
 * The design's dock rising in for a page's selection bar or Unhide button: 28dp up and in over 300 ms with a slight
 * overshoot. It goes at once, so its exit is [ExitTransition.None]. Nothing moves with Remove animations on.
 */
@Composable
internal fun dockRise(): EnterTransition {
    if (AlbumTheme.spec.still) return EnterTransition.None
    val rise = with(LocalDensity.current) { 28.dp.roundToPx() }
    val ease = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.1f)
    return slideInVertically(tween(300, easing = ease)) { rise } + fadeIn(tween(300, easing = ease))
}

/**
 * The safe's grid (and ID cards'): [list], newest first, under day headers as the Library's ("Today", "Sat, 24 Oct"),
 * or months at its month zoom, with as many tiles across as the Library shows. Tap views an item ([onView]), or picks it
 * while [selected] isn't empty; long-press picks. [bottom]: room under the last row.
 */
@Composable
internal fun SafeGrid(list: List<SafeItem>, key: ByteArray, selected: Set<Long>, onSelect: (Set<Long>) -> Unit, onView: (Long) -> Unit, bottom: Dp) {
    val spec = AlbumTheme.spec
    val haptics = rememberHaptics()
    val columns = rememberColumns(LIBRARY_COLUMNS).intValue
    val byMonth = columns >= MONTH_COLUMNS
    val zone = remember { ZoneId.systemDefault() }
    // Off the main thread: each item's day is a time zone conversion, ~100 ms for 20,000 items, again after every change.
    // Until the first grouping is done the grid is empty for a frame or two; after a change it shows the last one meanwhile.
    val sections by produceState(emptyList<Section<SafeItem>>(), list, byMonth) {
        value = withContext(Dispatchers.Default) { sections(list, byMonth) { Instant.ofEpochMilli(it.taken).atZone(zone).toLocalDate().toEpochDay() } }
    }
    val selecting = selected.isNotEmpty()
    LazyVerticalGrid(
        GridCells.Fixed(columns),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(spec.gutter),
        verticalArrangement = Arrangement.spacedBy(spec.gutter),
    ) {
        sections.forEachIndexed { i, section ->
            item(key = "day ${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                // The Library's date header: 8dp from the top for the first, 32dp between days
                Text(
                    section.label,
                    Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = if (i == 0) Space.s2 else Space.s8, bottom = Space.s3)
                        .semantics { heading() },
                    style = AlbumTheme.type.titleMd, color = spec.colors.ink,
                )
            }
            items(section.items, key = { it.id }) { item ->
                val isSelected = item.id in selected
                SafeTile(
                    item, key, selecting, isSelected,
                    Modifier.aspectRatio(1f).combinedClickable(
                        onLongClickLabel = "Select",
                        onLongClick = {
                            haptics.longPress()
                            onSelect(selected + item.id)
                        },
                        hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
                    ) {
                        when {
                            selecting -> onSelect(if (isSelected) selected - item.id else selected + item.id)
                            else -> onView(item.id)
                        }
                    },
                )
            }
        }
    }
}

/** Just what [tileLabel] reads. A safe item has no MediaStore uri. */
private fun SafeItem.asMedia() = Media(
    id, Uri.EMPTY, isVideo, taken, Instant.ofEpochMilli(taken).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(),
    width, height, durationMs, 0, "", favorite, size, name, path,
)

/** A safe item in the grid, drawn like [MediaTile] but from its thumbnail decrypted into memory, never Coil's caches. */
@Composable
internal fun SafeTile(item: SafeItem, key: ByteArray, selecting: Boolean, selected: Boolean, modifier: Modifier) {
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
internal fun SafeViewer(items: List<SafeItem>, start: Int, key: ByteArray, onClose: () -> Unit) {
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
internal fun SafeProgress(p: Safe.Progress) {
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
