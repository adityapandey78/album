package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    // What a widget or the On this day notification asked to open, until AlbumShell has opened it
    private val link = MutableStateFlow<AlbumLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh start only: after a rotation or a restore it was opened already
        if (savedInstanceState == null) link.value = linkOf(intent)
        enableEdgeToEdge()
        AlbumPrefs.load(this)
        AppearancePrefs.load(this)
        SavePrefs.load(this)
        // Compose's animations take their duration scale from the recomposer: AlbumMotion adds the Design lab's Slow motion
        setContent(window.decorView.createLifecycleAwareWindowRecomposer(AlbumMotion, lifecycle)) {
            val appearance by AppearancePrefs.appearance.collectAsState()
            // Status bar icons follow Album's theme, not the phone's: a Light theme on a dark phone still needs dark icons
            val dark = resolvedTheme(appearance.theme) != ThemeMode.Light
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            // Disguised, the disguise's front shows until the privacy PIN, and again whenever Album leaves the screen
            AlbumTheme(appearance) { DisguiseGate { AlbumShell(link) } }
        }
    }

    // A widget or the notification tapped while Album is open
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkOf(intent)?.let { link.value = it }
    }

    override fun onStop() {
        super.onStop()
        // The home-screen icon's sun follows the accent, unless Album is disguised; switching it closes this task, so only
        // once Album is out of sight
        if (!isChangingConfigurations) LauncherIcon.sync(this, AppearancePrefs.appearance.value.accent)
    }
}

internal enum class Tab(val sym: Sym, val label: String) {
    Library(Sym.PhotoLibrary, "Library"),
    Collections(Sym.CollectionsBookmark, "Collections"),
    ForYou(Sym.Explore, "For you"),
}

/** A screen opened over the tabs. Deeper screens slide in from the right and Back pops them in order. */
internal sealed class Route(val key: String) {
    /** An album: a folder, Favourites or Videos, a collection, a place, a thing or a person. */
    data class Open(val id: Long) : Route("open:$id")
    /** A group of albums (AlbumGroups.kt), by its group id. */
    data class Group(val id: Long) : Route("group:$id")
    data object Albums : Route("albums")
    data object Places : Route("places")
    data object Things : Route("things")

    /** The map of your photos, opened on one photo from its details ([focus]) or fitted to them all. */
    data class Map(val focus: MapFocus? = null) : Route(focus?.let { "map:${it.mediaId},${it.lat},${it.lng}" } ?: "map")
    data object Storage : Route("storage")
    data object PrivateSafe : Route("safe") // not "Safe": that's the object in Safe.kt
    data object IdCardsPage : Route("idcards") // not "IdCards": that's the object in IdCards.kt
    data object HiddenItems : Route("hidden") // not "Hidden": that's the object in Hidden.kt
    data object DriveBackup : Route("backup") // not "Backup": that's the object in Backup.kt

    /** Clean up, on one category (a CleanupKind name) or the suggested ones. */
    data class Clean(val category: String?) : Route("cleanup:${category.orEmpty()}")
    data object People : Route("people")
    data object PeopleReview : Route("people-review")
    data object Trash : Route("trash")
    data object Search : Route("search")
    data object Settings : Route("settings")

    // Pages pushed from Settings
    data object Appearance : Route("appearance")
    data object DisguisePage : Route("disguise") // not "Disguise": that's the enum in Disguise.kt
    data object Describe : Route("describe")
    data object Updates : Route("updates")
    data object Diagnostics : Route("diagnostics")
    data object About : Route("about")
    data object Licences : Route("licences")
    data class SettingsSub(val page: SettingsSection) : Route("settings:${page.name}")

    companion object {
        fun of(key: String): Route? = key.removePrefix("open:").toLongOrNull()?.takeIf { key.startsWith("open:") }?.let(::Open)
            ?: key.removePrefix("group:").toLongOrNull()?.takeIf { key.startsWith("group:") }?.let(::Group)
            ?: key.removePrefix("cleanup:").takeIf { key.startsWith("cleanup:") }?.let { Clean(it.ifEmpty { null }) }
            ?: SettingsSection.entries.find { "settings:${it.name}" == key }?.let(::SettingsSub)
            ?: key.takeIf { it == "map" }?.let { Map() }
            ?: key.removePrefix("map:").takeIf { key.startsWith("map:") }?.split(',')?.takeIf { it.size == 3 }
                ?.let { (id, lat, lng) -> Map(MapFocus(id.toLong(), lat.toDouble(), lng.toDouble())) }
            ?: listOf(Albums, Places, Things, People, PeopleReview, Trash, Search, Settings, Storage, PrivateSafe, IdCardsPage, HiddenItems, DriveBackup, Appearance, DisguisePage, Describe, Updates, Diagnostics, About, Licences)
                .find { it.key == key }
    }
}

// Saved as keys so the open screens come back after Android recreates the activity
private val StackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack -> stack.map { it.key } },
    restore = { keys -> keys.mapNotNull(Route::of).toMutableStateList() },
)

/** Full = all photos. Partial = Android 14+ "Select photos" (only the ones you picked). */
private enum class Access { None, Partial, Full }

private fun accessOf(context: Context): Access {
    fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        has(READ_MEDIA_IMAGES) && has(READ_MEDIA_VIDEO) -> Access.Full
        Build.VERSION.SDK_INT >= 34 && has(READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
        else -> Access.None
    }
}

private val mediaPermissions = buildList {
    add(READ_MEDIA_IMAGES)
    add(READ_MEDIA_VIDEO)
    if (Build.VERSION.SDK_INT >= 34) add(READ_MEDIA_VISUAL_USER_SELECTED)
    add(ACCESS_MEDIA_LOCATION) // lets the smart index read where photos were taken (EXIF GPS), for Places
}.toTypedArray()

/** What an open album page (or Clean up, or Storage) shows, reported up so the viewer and the selection bar work on the same photos. */
private class PageInfo(val key: String, val items: List<Media>, val collection: CollectionAlbum?, val person: PersonAlbum?, val title: String? = null)

/** A memory or album playing as a story; [from] is the card it was played from, in window coordinates. */
private class Story(val items: List<Media>, val title: String?, val eyebrow: String?, val from: Rect? = null)

/** Set once the first-run tip's "Got it" is tapped. */
private const val TIP_SEEN = "tip_seen"

@Composable
private fun AlbumShell(link: MutableStateFlow<AlbumLink?>) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val c = spec.colors
    var access by remember { mutableStateOf(accessOf(context)) }
    LifecycleResumeEffect(Unit) {
        access = accessOf(context) // the user may have changed it in system settings
        onPauseOrDispose {}
    }
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access = accessOf(context) }
    LaunchedEffect(access) { if (access != Access.None) Gallery.start(context) }
    // Phones upgrading from v0.3 already had photo access before Places existed, so ask for photo locations too.
    // No dialog: Android grants ACCESS_MEDIA_LOCATION silently to apps that hold photo access.
    LaunchedEffect(access) {
        if (access != Access.None && context.checkSelfPermission(ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askAccess.launch(arrayOf(ACCESS_MEDIA_LOCATION))
        }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    val stack = rememberSaveable(saver = StackSaver) { mutableStateListOf<Route>() }
    val top = stack.lastOrNull()
    // The stack as drawn: each screen's slide, growth or fade, and the back swipe
    val stackScope = rememberCoroutineScope()
    val screens = remember { Screens(stackScope, stack.toList()) }
    LaunchedEffect(stack.toList()) { screens.match(stack.toList()) } // a link or a relock changed it directly
    val push = { r: Route ->
        // A second tap on the same card during the slide (the screen beneath still shows part way) opens nothing new
        if (stack.lastOrNull() != r) {
            // Opened from a card (opensScreen), it grows out of the card
            screens.push(r, if (r == Route.Search) null else OpenedFrom.take(), spec.still)
            stack.add(r)
            CrashLog.note("open ${r.key.substringBefore(':')}")
        }
    }
    val pop = {
        screens.pop(spec.still)
        stack.removeLastOrNull()
        Unit
    }
    // Each screen keeps its saved state (where it was scrolled, say) while deeper ones cover it, so Back lands where it was
    val routeStates = rememberSaveableStateHolder()
    // Recently deleted opens as its own screen wherever its id comes from
    val openId = { id: Long -> push(if (id == Special.Trash.id) Route.Trash else Route.Open(id)) }

    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var selectMode by remember { mutableStateOf(false) } // Select in a top bar: selecting, with nothing picked yet
    val clearSelection = {
        selected = emptySet()
        selectMode = false
    }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var jumpDay by rememberSaveable { mutableStateOf<Long?>(null) } // a day picked in the calendar, until the Library is there
    var libraryMenu by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf<List<Media>?>(null) } // the selection, while "Add to" is open
    var moreMenu by remember { mutableStateOf<List<Media>?>(null) } // the selection, while its More is open
    var addingToCollection by remember { mutableStateOf<List<Long>?>(null) }
    var story by remember { mutableStateOf<Story?>(null) }
    // A memory or album played from a card grows out of it
    val play = { title: String?, eyebrow: String?, items: List<Media> -> story = Story(items, title, eyebrow, OpenedFrom.take()) }
    var barHidden by remember { mutableStateOf(false) }
    val settings = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var tipSeen by remember { mutableStateOf(settings.getBoolean(TIP_SEEN, false)) }

    // Nothing shows until it's known what's in Hidden items, so none of it ever flashes up at launch
    val hiddenItems by Hidden.state.collectAsState()
    val loaded = Gallery.media.collectAsState().value?.takeIf { hiddenItems != null }
    val everything = loaded.orEmpty()
    // Every photo and video Album shows, minus Hidden items; only Hidden items' own pages see past it
    val media = remember(everything, hiddenItems) { hiddenItems?.let { everything.withoutHidden(it) } ?: everything }
    val privacyOpen = Safe.key.collectAsState().value != null
    val hidden by AlbumPrefs.hidden.collectAsState()
    val photos = remember(media, hidden) { media.inPhotos(hidden) } // the Library leaves out folders hidden from it
    val trash = if (access != Access.None) rememberTrash().value else null
    val albums = remember(media) { albumsOf(media) }
    val specials = remember(media, trash) { specialCounts(media, trash.orEmpty().size) }
    val collections = rememberCollections(media)
    // Opened once the Library and Hidden items have loaded, and only among what Album shows: any app can send a link
    val pending by link.collectAsState()
    LaunchedEffect(pending, loaded != null) {
        val l = pending ?: return@LaunchedEffect
        if (loaded == null) return@LaunchedEffect
        link.value = null
        CrashLog.note("open link ${l.javaClass.simpleName}")
        clearSelection()
        viewerId = null
        when (l) {
            is AlbumLink.OpenAlbum -> if (hiddenItems?.isHiddenAlbum(l.albumId, everything) != true) {
                story = null
                stack.clear() // Back from it lands on the tabs
                screens.match(stack) // so the album slides in over the tabs, not over what was open
                push(Route.Open(l.albumId))
            }
            is AlbumLink.PlayMemory -> {
                val byId = media.associateBy { it.id }
                val items = l.ids.mapNotNull { byId[it] }
                if (items.isNotEmpty()) story = Story(items, l.title, l.eyebrow)
            }
        }
    }

    // Pages report what they show, so the viewer and the selection bar act on the photos on screen
    var page by remember { mutableStateOf<PageInfo?>(null) }
    var searchState by remember { mutableStateOf<SearchState?>(null) }
    val shown = when (top) {
        is Route.Open, is Route.Clean, Route.Storage -> page?.takeIf { it.key == top.key }?.items.orEmpty()
        Route.DriveBackup -> media // the not-backed-up grid opens photos from the whole Library
        Route.Search -> searchState?.items.orEmpty()
        null -> if (tab == Tab.Library) photos else emptyList()
        else -> emptyList()
    }
    val openCollection = page?.takeIf { top is Route.Open && it.key == top.key }?.collection
    val openPerson = page?.takeIf { top is Route.Open && it.key == top.key }?.person

    // Grid states live here so scroll position and zoom survive the viewer, tab switches and the screens above them
    val photosGrid = rememberLazyGridState()
    val photosColumns = rememberColumns("columns_photos")
    val albumColumns = rememberColumns("columns_album")
    // One per open album, dropped when it closes. Not snapshot state: only read when a photo opens or closes.
    val albumGrids = remember { HashMap<String, LazyGridState>() }
    val searchGrid = rememberLazyGridState()
    val searchColumns = rememberColumns("columns_search")
    val collectionsGrid = rememberLazyGridState()
    val forYouList = rememberLazyListState()
    val peopleGrid = rememberLazyGridState()
    val albumsGrid = rememberLazyGridState()
    val placesGrid = rememberLazyGridState()
    LaunchedEffect(stack.toList()) { albumGrids.keys.retainAll(stack.mapTo(HashSet()) { it.key }) }

    val snackbar = remember { SnackbarHostState() }
    // Messages from code with no host of its own (Media.kt's checks), while Album is on screen
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { Messages.posts.collect { launch { snackbar.showSnackbar(it) } } }
    }
    val trashWithUndo = rememberTrashWithUndo(snackbar) { clearSelection() }
    // Always composed: it also finishes a move into the safe that the app was closed in the middle of.
    // The selection clears only once the fingerprint is given, so a cancelled prompt leaves it as it was.
    val moveToSafe = rememberMoveToSafe(snackbar) { clearSelection() }
    val moveToIdCards = rememberMoveToIdCards(snackbar) { clearSelection() }
    PrivacyHost(snackbar) // hide and unhide results with Undo, and the privacy lock itself
    // Locking privacy (leaving Album, Lock now, 2 minutes after the last private thing) closes what Hidden items opened
    LaunchedEffect(privacyOpen, hiddenItems) {
        if (!privacyOpen) stack.removeAll { it is Route.Open && hiddenItems?.isHiddenAlbum(it.id, everything) == true }
    }
    val cleanup by Cleanup.summary.collectAsState()
    val albumActions = rememberAlbumActions { clearSelection() }
    val addToCollection = rememberAddToCollection(snackbar)
    val removeFromCollection = rememberRemoveFromCollection(snackbar)
    val notPerson = rememberNotPerson(snackbar) { id -> if (stack.lastOrNull() is Route.Open) stack[stack.lastIndex] = Route.Open(id) }
    val favourite = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { clearSelection() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // Selection belongs to one screen: switching tabs or opening and closing screens ends it
    LaunchedEffect(tab, top?.key) { clearSelection() }
    // Photos deleted by another app leave the selection, so "N selected" and its actions stay truthful
    LaunchedEffect(shown) {
        if (selected.isEmpty()) return@LaunchedEffect // the usual case: no set of the whole library on every navigation
        val ids = shown.mapTo(HashSet()) { it.id }
        if (!ids.containsAll(selected)) selected = selected.filterTo(HashSet()) { it in ids }
    }
    LaunchedEffect(tab, top) { barHidden = false }
    val selecting = selectMode || selected.isNotEmpty()
    val select = { ids: Set<Long> ->
        selected = ids
        if (ids.isEmpty()) selectMode = false // unpicking the last one ends selecting, as in the design
        else barHidden = false // the full bar comes back when selecting ends
    }
    val startSelecting = {
        selectMode = true
        barHidden = false
        CrashLog.note("select")
    }

    // Where the visible grid sits on screen, so a photo can fly out of its cell into the viewer and back into it
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var openedFrom by remember { mutableStateOf<Rect?>(null) }
    val gridPosition = Modifier.onGloballyPositioned { gridOrigin = it.positionInWindow() }
    val cellOnScreen = cell@{ id: Long ->
        // the grid that's on screen; none for screens without one, so the viewer fades instead
        val grid = when (top) {
            Route.Search -> searchGrid
            is Route.Open -> albumGrids[top.key]
            null -> if (tab == Tab.Library) photosGrid else null
            else -> null
        } ?: return@cell null
        cellBounds(grid, id)?.translate(gridOrigin) // grid coordinates, in every gallery style
    }
    val open = { m: Media ->
        openedFrom = cellOnScreen(m.id)
        CrashLog.note("open ${if (m.isVideo) "video" else "photo"}")
        viewerId = m.id
    }
    BackHandler(selecting) { clearSelection() }
    // Back pops the top screen. A back swipe from the left edge pulls it with the finger while the one beneath slides back
    // from 25% left and brightens; let go short of Android's threshold and both settle back.
    PredictiveBackHandler(top != null && !selecting) { events ->
        var swiping = false
        var buzzed = false
        try {
            events.collect { e ->
                if (e.swipeEdge != BackEventCompat.EDGE_LEFT) return@collect
                swiping = true
                if (!buzzed && e.progress > 0.35f) {
                    buzzed = true
                    haptics.threshold()
                }
                screens.drag(e.progress)
            }
            if (swiping) {
                screens.pop(spec.still, swiped = true)
                stack.removeLastOrNull()
            } else pop()
        } catch (e: CancellationException) {
            if (swiping) screens.cancelSwipe(spec.still)
            throw e
        }
    }

    val density = LocalDensity.current
    // Only the Library shrinks the bottom bar: on a scroll step of 10dp down once it's 160dp from the top; any 10dp up
    // brings it back
    val barOnScroll = remember(photosGrid, density) {
        val step = with(density) { 10.dp.toPx() }
        val far = with(density) { 160.dp.roundToPx() }
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val past = photosGrid.firstVisibleItemIndex > 0 || photosGrid.firstVisibleItemScrollOffset > far
                if (available.y < -step && past) barHidden = true else if (available.y > step) barHidden = false
                return Offset.Zero
            }
        }
    }

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Scrolling content clears the floating bar: bar height plus space-8, above the gesture area
    val tabPadding = PaddingValues(top = statusBar, bottom = navBar + Space.s4 + Space.bar + Space.s8)
    var revealFrom by remember { mutableStateOf(Offset.Unspecified) }
    val backdrop = rememberGraphicsLayer()

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        if (access == Access.None) {
            PermissionScreen { askAccess.launch(mediaPermissions) }
            return@Box
        }
        // Once, after photo access: where Album saves what it makes. Photos keep loading meanwhile.
        if (!SavePrefs.asked.collectAsState().value) {
            SaveLocationStep()
            return@Box
        }

        @Composable
        fun TabPage(t: Tab) {
            Box(Modifier.fillMaxSize().background(c.canvas).then(if (t == Tab.Library) Modifier.nestedScroll(barOnScroll) else Modifier)) {
                when (t) {
                    Tab.Library -> LibraryPage(
                        // only the grid on top reports where it is: a covered Library sits 25% to the left
                        photos, loaded != null, photosColumns, photosGrid, selected, open, select, tabPadding,
                        if (top == null) gridPosition else Modifier,
                        jumpDay = jumpDay,
                        onJumped = { jumpDay = null },
                        partial = access == Access.Partial,
                        onAllowAll = { askAccess.launch(mediaPermissions) },
                        selectMode = selectMode,
                        onStartSelecting = startSelecting,
                        onCalendar = {
                            calendarOpen = true
                            CrashLog.note("open calendar")
                        },
                        onMore = { libraryMenu = true },
                    )
                    Tab.Collections -> CollectionsScreen(
                        albums, specials, collections, media, albumActions, collectionsGrid, tabPadding,
                        onOpen = { id ->
                            CrashLog.note("open album ${Special.entries.find { it.id == id }?.name ?: if (collectionIdOf(id) != null) "collection" else "other"}")
                            openId(id)
                        },
                        onOpenGroup = { push(Route.Group(it)) },
                        onAllAlbums = { push(Route.Albums) },
                        onPeople = { push(Route.People) },
                        onPlaces = { push(Route.Places) },
                        onThings = { push(Route.Things) },
                        onSettings = { push(Route.Settings) },
                    )
                    Tab.ForYou -> ForYouScreen(
                        photos, forYouList, tabPadding,
                        cleanupCount = cleanup?.toReview ?: 0,
                        onPlay = { title, eyebrow, items ->
                            CrashLog.note("play memory")
                            play(title, eyebrow, items)
                        },
                        onCleanup = { push(Route.Clean(null)) },
                        onStorage = { push(Route.Storage) },
                        // A false positive must not leave the Library on one tap: confirm with what will move
                        onProtect = { items -> moveToIdCards(items, ask = true) },
                        onSettings = { push(Route.Settings) },
                    )
                }
            }
        }

        // A screen opened over the tabs: its saved state (where it was scrolled, say) is kept while deeper ones cover it
        @Composable
        fun RouteScreen(route: Route) {
            // A closed screen opens fresh next time: its state goes once it has gone, not while it goes out (Back and
            // forward again inside the slide brings back this same composition, which must keep saving)
            DisposableEffect(route.key) {
                onDispose { if (stack.none { it.key == route.key }) routeStates.removeState(route.key) }
            }
            routeStates.SaveableStateProvider(route.key) {
                when (route) {
                    is Route.Open -> {
                        // A hidden album or person, opened from Hidden items while privacy is unlocked, shows its photos
                        val private = privacyOpen && hiddenItems?.isHiddenAlbum(route.id, everything) == true
                        if (private) PrivatePage()
                        AlbumPage(
                            route, if (private) everything else media,
                            if (private) remember(everything) { albumsOf(everything) } else albums,
                            collections, loaded != null, albumColumns,
                            albumGrids.getOrPut(route.key) { LazyGridState() }, selected, open, select,
                            tabPadding, gridPosition,
                            onBack = pop,
                            onOpenPerson = { id -> stack[stack.lastIndex] = Route.Open(id) }, // merging leaves you on the merged person
                            selectMode = selectMode,
                            onStartSelecting = startSelecting,
                            report = { page = it },
                            albumActions = albumActions,
                            onPlay = { title, eyebrow, items -> play(title, eyebrow, items) },
                        )
                    }
                    is Route.Group -> AlbumGroupScreen(
                        route.id, albums, collections, media, albumActions, albumGrids.getOrPut(route.key) { LazyGridState() },
                        onBack = pop, onOpen = openId,
                        onPlay = play,
                    )
                    Route.HiddenItems -> HiddenItemsScreen(onBack = pop, onOpen = openId)
                    Route.Albums -> AllAlbumsScreen(
                        albums, collections, media, albumActions, albumsGrid, onBack = pop, onOpen = openId,
                        onOpenGroup = { push(Route.Group(it)) },
                    )
                    Route.Things -> ThingsScreen(onBack = pop, onOpen = openId)
                    Route.Places -> PlacesScreen(placesGrid, onBack = pop, onOpen = openId, onMap = { push(Route.Map()) })
                    is Route.Map -> MapScreen(onBack = pop, onOpen = openId, focus = route.focus)
                    // Clean up and Storage get every item: WhatsApp may live in folders hidden from the Library
                    is Route.Clean -> CleanupScreen(media, onBack = pop, onOpen = open, category = route.category, onStorage = { if (stack.getOrNull(stack.lastIndex - 1) == Route.Storage) pop() else push(Route.Storage) }, report = { page = PageInfo(route.key, it, null, null) })
                    Route.Storage -> StorageScreen(media, onBack = pop, onOpenCategory = { push(Route.Clean(it)) }, onOpen = open, report = { page = PageInfo(route.key, it, null, null) })
                    Route.PrivateSafe -> SafeScreen(onBack = pop)
                    Route.IdCardsPage -> IdCardsScreen(onBack = pop)
                    Route.People -> PeopleScreen(peopleGrid, onBack = pop, onOpenPerson = openId, onReviewPeople = { push(Route.PeopleReview) })
                    Route.PeopleReview -> PeopleReviewScreen(onBack = pop)
                    Route.Trash -> TrashScreen(trash, photosColumns, onBack = pop)
                    Route.Settings -> SettingsScreen(
                        onBack = pop, columns = photosColumns.intValue, onAppearance = { push(Route.Appearance) },
                        onSection = { push(Route.SettingsSub(it)) }, onAbout = { push(Route.About) },
                    )
                    is Route.SettingsSub -> SettingsSectionScreen(
                        route.page, onBack = pop, columns = photosColumns,
                        onOpenSafe = { push(Route.PrivateSafe) }, onHiddenItems = { push(Route.HiddenItems) }, onDisguise = { push(Route.DisguisePage) },
                        onOpenTrash = { push(Route.Trash) }, onDescribe = { push(Route.Describe) }, onBackup = { push(Route.DriveBackup) },
                        onIdCards = { push(Route.IdCardsPage) }, snackbar = snackbar,
                    )
                    Route.Appearance -> AppearanceScreen(onBack = pop)
                    Route.DisguisePage -> DisguiseScreen(onBack = pop)
                    Route.DriveBackup -> BackupScreen(onBack = pop, onOpen = open)
                    Route.Describe -> DescribeScreen(onBack = pop)
                    Route.Updates -> UpdatesScreen(onBack = pop)
                    Route.Diagnostics -> DiagnosticsScreen(onBack = pop)
                    Route.About -> AboutScreen(
                        onBack = pop, onUpdates = { push(Route.Updates) }, onDiagnostics = { push(Route.Diagnostics) },
                        onLicences = { push(Route.Licences) },
                    )
                    Route.Licences -> LicencesScreen(onBack = pop)
                    Route.Search -> {
                        val s = rememberSearch(photos, collections)
                        DisposableEffect(s) {
                            searchState = s
                            onDispose { if (searchState === s) searchState = null }
                        }
                        SearchPage(s, searchColumns, searchGrid, selected, open, select, selecting, onBack = pop, gridPosition)
                    }
                }
            }
        }

        // Everything the floating chrome floats over, recorded for its blur
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            // The tabs stay composed under deeper screens, so Back to them is instant: no grid layout rebuilt, the scroll
            // where it was. Covered, they dim under the scrim (and slip 25% left under a screen that slid in), and TalkBack
            // skips them.
            val covered = top != null
            val under = screens.tabs
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationX = if (!spec.still && under.slides) -size.width / 4 * under.slip.value else 0f }
                    .drawWithContent {
                        drawContent()
                        val dim = under.dim.value
                        if (dim > 0f) drawRect(c.scrim, alpha = dim / 2)
                    }
                    .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier),
            ) { Tabs(tab, spec, revealFrom, onTab = { tab = it }) { TabPage(it) } }

            // Deeper screens over the tabs and over each other: the top one, the one beneath while the top one comes in or
            // is swiped, and any still going out on top
            val layers = screens.layers
            val drawn = buildList {
                val n = layers.size
                if (n >= 2 && screens.underShows && layers[n - 2].route.key != layers[n - 1].route.key) add(layers[n - 2])
                layers.lastOrNull()?.let(::add)
                addAll(screens.leaving)
            }
            val upper = layers.lastOrNull()
            for (layer in drawn) {
                key(layer.id) { ScreenLayer(layer, covered = layer !== upper) { RouteScreen(layer.route) } }
            }
        }

        // The status bar always sits on canvas, so photos never scroll under its icons
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.canvas))

        // Bottom chrome: the selection bar replaces the bottom bar in place; deeper screens let it sink away
        // Scrolling down the Library shrinks the bar to the current tab and search, hides it, or leaves it (Settings)
        val scrollMode = spec.appearance.barScroll
        val hiddenByScroll = barHidden && scrollMode == BarScroll.Hide
        val classic = spec.appearance.bar == BarStyle.Classic
        // Off the bottom edge: 160% of its height, past the 16dp gap and the navigation bar
        val dockOff = with(density) { (Space.s4 + navBar).roundToPx() }
        val dockSlide = tween<IntOffset>(300, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f))
        val firstTip = !tipSeen && tab == Tab.Library && top == null && !selecting && viewerId == null
        // The first-run tip, above the bar until "Got it"
        AnimatedVisibility(
            firstTip,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = Space.s3, end = Space.s3, bottom = Space.s4 + Space.bar + Space.s3),
            enter = if (spec.still) EnterTransition.None else slideInVertically(tween(300, easing = Ease)) { with(density) { 28.dp.roundToPx() } } + fadeIn(tween(300, easing = Ease)),
            exit = ExitTransition.None,
        ) {
            FirstRunTip {
                tipSeen = true
                settings.edit { putBoolean(TIP_SEEN, true) }
            }
        }
        // The bar, the selection bar and messages float on a blur of the screens behind them
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Box(Modifier.align(Alignment.BottomCenter).then(if (classic && !selecting) Modifier else Modifier.navigationBarsPadding().padding(bottom = Space.s4))) {
                // Deeper screens (and Hide on scroll) slide the bar off the bottom edge and back, fully opaque
                AnimatedVisibility(
                    top == null && !hiddenByScroll,
                    enter = if (spec.still) EnterTransition.None else slideInVertically(dockSlide) { (it * 1.6f).roundToInt() + dockOff },
                    exit = if (spec.still) ExitTransition.None else slideOutVertically(dockSlide) { (it * 1.6f).roundToInt() + dockOff },
                ) {
                    // The selection bar takes its place at once; when selecting ends the bar rises back and its tab pops
                    AnimatedVisibility(!selecting, enter = dockEnter(), exit = ExitTransition.None) {
                        val popIn = remember { transition.currentState == EnterExitState.PreEnter }
                        BottomBar(
                            Tab.entries.map { NavItem(it.sym, it.label) },
                            tab.ordinal,
                            mini = barHidden && scrollMode == BarScroll.Shrink,
                            onExpand = {
                                haptics.tick()
                                barHidden = false
                            },
                            onSelect = { i, center ->
                                val t = Tab.entries[i]
                                if (t == tab) {
                                    // Tapping the current tab scrolls it back to the top
                                    scope.launch {
                                        when (t) {
                                            Tab.Library -> if (spec.still) photosGrid.scrollToItem(0) else photosGrid.animateScrollToItem(0)
                                            Tab.Collections -> if (spec.still) collectionsGrid.scrollToItem(0) else collectionsGrid.animateScrollToItem(0)
                                            Tab.ForYou -> if (spec.still) forYouList.scrollToItem(0) else forYouList.animateScrollToItem(0)
                                        }
                                    }
                                } else {
                                    haptics.tick()
                                    revealFrom = center
                                    tab = t
                                    CrashLog.note("tab ${t.name}")
                                }
                            },
                            onSearch = {
                                push(Route.Search)
                                CrashLog.note("open search")
                            },
                            popIn = popIn,
                        )
                    }
                }
                AnimatedVisibility(selecting, enter = dockEnter(), exit = ExitTransition.None) {
                    SelectionActions(
                        selected, shown, openCollection, openPerson, context,
                        onClear = { clearSelection() },
                        onSelectAll = {
                            CrashLog.note(if (shown.all { it.id in selected }) "clear all" else "select all")
                            selected = toggleAll(selected, shown.map { it.id })
                        },
                        onAlbum = { albumMenu = it },
                        onFavourite = { items ->
                            if (context.canSend(items.size, MAX_REQUEST, "favourite")) {
                                val on = !items.all { it.isFavorite } // like the heart in the viewer: all on, or all off
                                CrashLog.note("favourite ${items.size} $on")
                                favourite.launch(IntentSenderRequest.Builder(favoriteRequest(context.contentResolver, items, on)).build())
                            }
                        },
                        onRemove = { col, ids ->
                            removeFromCollection(col, ids)
                            clearSelection()
                        },
                        onNotPerson = { person, ids ->
                            notPerson(person, ids)
                            clearSelection()
                        },
                        onTrash = { items ->
                            CrashLog.note("trash ${items.size}")
                            trashWithUndo(items)
                        },
                        onMore = { moreMenu = it },
                    )
                }
            }
            // Messages rise just above whatever bar is at the bottom; on a deeper screen 8dp above the bar's line
            AlbumSnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(
                    bottom = Space.s4 + when {
                        selecting -> Space.bar * 2 + Space.s4
                        top == null || top == Route.Search -> Space.bar + Space.s3
                        else -> Space.s2
                    },
                ),
            )
        }

        AlbumActionsHost(albumActions, media, albums, snackbar)
        if (calendarOpen) {
            CalendarSheet(
                photos,
                onDismiss = { calendarOpen = false },
                onDay = { day ->
                    calendarOpen = false
                    tab = Tab.Library
                    jumpDay = day // the grid scrolls the day's header to the top and glows it once
                },
                onOpen = { m ->
                    calendarOpen = false
                    open(m)
                },
            )
        }
        if (libraryMenu) {
            LibraryMenu(
                photosColumns.intValue,
                onDismiss = { libraryMenu = false },
                onCalendar = {
                    libraryMenu = false
                    calendarOpen = true
                },
                onAppearance = {
                    libraryMenu = false
                    push(Route.Appearance)
                },
                onGallery = {
                    libraryMenu = false
                    push(Route.SettingsSub(SettingsSection.Library))
                },
                onSettings = {
                    libraryMenu = false
                    push(Route.Settings)
                },
            )
        }
        albumMenu?.let { items ->
            Sheet(onDismiss = { albumMenu = null }, title = "Add ${countOf(items.size, "item")} to") {
                MenuRow(Sym.DriveFileMove, "Move to album", { albumMenu = null; albumActions.moveOrCopy(items, Transfer.Move) }, sub = "The files move to that folder")
                MenuRow(Sym.ContentCopy, "Copy to album", { albumMenu = null; albumActions.moveOrCopy(items, Transfer.Copy) }, sub = "A copy goes to that folder")
                MenuRow(Sym.LibraryAdd, "Add to collection", { albumMenu = null; addingToCollection = items.map { it.id } }, sub = "No copies: one photo can be in many collections")
                MenuRow(Sym.Lock, "Private safe", { albumMenu = null; moveToSafe(items) }, sub = "Encrypted, opens with your fingerprint")
                MenuRow(Sym.IdCard, "ID cards", { albumMenu = null; moveToIdCards(items) }, sub = "Encrypted, opens with your screen lock")
            }
        }
        moreMenu?.let { items ->
            val title = page?.takeIf { top is Route.Open && it.key == top.key }?.title
            SelectionMoreSheet(items, title, albumActions) { moreMenu = null }
        }
        addingToCollection?.let { ids ->
            AddToCollectionSheet(ids.size, collections, onDismiss = { addingToCollection = null }, onPick = { id, name ->
                addToCollection(id, name, ids)
                addingToCollection = null
                clearSelection()
            })
        }
        story?.let { s ->
            key(s) { StoryLayer(s.from) { Slideshow(s.items, s.items.first().id, onClose = { story = null }, title = s.title, eyebrow = s.eyebrow) } }
        }
        // A recreated activity (rotation, dark mode, split screen) restores viewerId before the open album or search has
        // reported its photos; waiting for them keeps the viewer from closing itself on an empty list
        val shownReady = when (top) {
            is Route.Open, is Route.Clean, Route.Storage -> page?.key == top.key
            Route.Search -> searchState?.result != null
            else -> loaded != null
        }
        viewerId?.takeIf { shownReady }?.let {
            Viewer(
                shown,
                it,
                onClose = { viewerId = null },
                onTrash = if (openCollection == null) { m -> trashWithUndo(listOf(m)) } else null,
                onMoveToSafe = { m -> moveToSafe(listOf(m)) },
                openFrom = openedFrom,
                closeTo = cellOnScreen,
                onEditDate = { m -> albumActions.editDate(listOf(m)) },
                onEditPlace = { m -> albumActions.editPlace(listOf(m)) },
                onShowOnMap = { f ->
                    viewerId = null // the map opens on its own, not under the viewer
                    push(Route.Map(f))
                },
                onAddToCollection = { m -> addingToCollection = listOf(m.id) },
            )
            // The main snackbar sits under the viewer, so the viewer gets its own host for Undo
            AlbumSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = viewerSnackbarGap), overViewer = true)
        }
    }
}

// ---------- Motion ----------

private val enterEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // tab content settling in
private val exitEase = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // anything leaving: quick to get out of the way
private val screenEase = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f) // screens and sheets arriving

// ---------- The screen stack ----------

/** How a screen comes in, and so how Back takes it out: slides from the right, grows out of its card, or fades. */
internal enum class Entry { Slide, Grow, Fade }

/**
 * How covered a screen (or the tabs) is by the one above it: [dim] darkens it under the scrim at half strength, [slip]
 * moves it 25% left, but only when the screen above slid in ([slides]).
 */
@Stable
internal class Cover(covered: Boolean) {
    val slip = Animatable(if (covered) 1f else 0f)
    val dim = Animatable(if (covered) 1f else 0f)
    var slides by mutableStateOf(covered)
}

/**
 * A screen on the stack as drawn. [shown] runs from 0 (off to the right, inside its card, or transparent) to 1 (in place);
 * [content] fades a grown screen's content in after its outline; [swipe] is how far a back swipe has pulled it right, as
 * a share of the width. [from] is the card it grew out of, in window coordinates.
 */
@Stable
internal class Layer(val id: Int, val route: Route, val entry: Entry, val from: Rect?, shown: Float, covered: Boolean) {
    val shown = Animatable(shown)
    val content = Animatable(if (entry == Entry.Grow) shown else 1f)
    val swipe = Animatable(0f)
    val cover = Cover(covered)
}

/**
 * The screens over the tabs, as drawn. [layers] follow the stack; a popped screen moves to [leaving] until it has gone
 * back out. Push and pop animate as the design does: a screen slides in over 400ms while the one beneath slips 25% left
 * and dims, and slides out in 280ms while that one returns over 400ms; opened from a card it grows out of the card in
 * 440ms (its content fading in after 120ms) and shrinks back into it in 300ms, the one beneath only dimming; search
 * fades. A back swipe moves the top screen with the finger. Any other change to the stack (a link, a relock) swaps at
 * once ([match]). Remove animations: screens crossfade in 120ms, out in 100ms, and nothing beneath moves.
 */
@Stable
internal class Screens(private val scope: CoroutineScope, stack: List<Route>) {
    private var nextId = 0
    val layers = mutableStateListOf<Layer>().apply {
        stack.forEachIndexed { i, r -> add(Layer(nextId++, r, Entry.Slide, null, 1f, covered = i < stack.lastIndex)) }
    }
    val leaving = mutableStateListOf<Layer>()
    val tabs = Cover(stack.isNotEmpty())

    /** Whether the screen under the top one shows: while the top one comes in, or a back swipe pulls it aside. */
    val underShows by derivedStateOf { layers.lastOrNull()?.let { it.shown.value < 1f || it.swipe.value > 0f } == true }

    private val below get() = layers.getOrNull(layers.lastIndex - 1)?.cover ?: tabs

    fun push(route: Route, from: Rect?, still: Boolean) {
        val under = layers.lastOrNull()?.cover ?: tabs
        // Back and then forward again while it was still going: the same screen comes back, as it was
        val back = leaving.lastOrNull { it.route == route }
        val entry = when {
            still || route == Route.Search -> Entry.Fade
            from != null -> Entry.Grow
            else -> Entry.Slide
        }
        val layer = back?.also { leaving.remove(it) } ?: Layer(nextId++, route, entry, from, 0f, covered = false)
        layers.add(layer)
        dropLeavingShadows()
        under.slides = layer.entry == Entry.Slide && !still
        scope.launch { enter(layer, still) }
        scope.launch { cover(under, still) }
    }

    /** Back: the top screen goes out the way it came in, or, after a [swiped] back swipe, on to the right. */
    fun pop(still: Boolean, swiped: Boolean = false) {
        val layer = layers.removeLastOrNull() ?: return
        val under = layers.lastOrNull()?.cover ?: tabs
        leaving.add(layer)
        dropLeavingShadows()
        scope.launch {
            exit(layer, still, swiped)
            leaving.remove(layer)
        }
        scope.launch { uncover(under, still, swiped) }
    }

    /** A back swipe at [progress] (0 to 1): the top screen follows the finger, the one beneath slides back and brightens. */
    suspend fun drag(progress: Float) {
        val layer = layers.lastOrNull() ?: return
        val under = below
        layer.swipe.snapTo(progress)
        if (under.slides) under.slip.snapTo(1f - progress)
        under.dim.snapTo(1f - progress)
    }

    /** A back swipe let go of short of going back: both screens settle where they were (at once with Remove animations). */
    fun cancelSwipe(still: Boolean) {
        val layer = layers.lastOrNull() ?: return
        val under = below
        val settle = if (still) snap() else tween<Float>(240, easing = screenEase)
        scope.launch { layer.swipe.animateTo(0f, settle) }
        scope.launch { under.slip.animateTo(1f, settle) }
        scope.launch { under.dim.animateTo(1f, settle) }
    }

    /** Any other change to the stack: screens still in their place stay as they are, the rest swap in at once. */
    fun match(stack: List<Route>) {
        if (layers.map { it.route } == stack) return
        val old = layers.toList()
        layers.clear()
        stack.forEachIndexed { i, r -> layers.add(old.getOrNull(i)?.takeIf { it.route == r } ?: Layer(nextId++, r, Entry.Slide, null, 1f, covered = false)) }
        dropLeavingShadows()
        val now = layers.toList() // as matched: a push straight after animates on its own
        scope.launch {
            now.forEachIndexed { i, l ->
                val covered = if (i < now.lastIndex) 1f else 0f
                l.shown.snapTo(1f)
                l.content.snapTo(1f)
                l.swipe.snapTo(0f)
                l.cover.slip.snapTo(covered)
                l.cover.dim.snapTo(covered)
            }
            val covered = if (now.isEmpty()) 0f else 1f
            tabs.slip.snapTo(covered)
            tabs.dim.snapTo(covered)
        }
    }

    // A screen can't be drawn twice at once (its saved state is keyed by its route): one still going out makes way for
    // the same route on the stack
    private fun dropLeavingShadows() {
        val keys = layers.mapTo(HashSet()) { it.route.key }
        leaving.removeAll { it.route.key in keys && it !in layers }
    }

    private suspend fun enter(layer: Layer, still: Boolean) = coroutineScope {
        if (layer.swipe.value != 0f) launch { layer.swipe.animateTo(0f, tween(240, easing = screenEase)) }
        when {
            still || layer.entry == Entry.Fade -> layer.shown.animateTo(1f, tween(if (still) 120 else 220, easing = Ease))
            layer.entry == Entry.Grow -> {
                launch { layer.content.animateTo(1f, tween(300, delayMillis = 120, easing = Ease)) }
                layer.shown.animateTo(1f, tween(440, easing = screenEase))
            }
            else -> layer.shown.animateTo(1f, tween(400, easing = screenEase))
        }
    }

    private suspend fun exit(layer: Layer, still: Boolean, swiped: Boolean) = coroutineScope {
        when {
            swiped -> layer.swipe.animateTo(1f, if (still) snap() else tween(240, easing = screenEase))
            still || layer.entry == Entry.Fade -> layer.shown.animateTo(0f, tween(if (still) 100 else 150, easing = Ease))
            layer.entry == Entry.Grow -> {
                launch { layer.content.animateTo(0f, tween(160, easing = Ease)) }
                layer.shown.animateTo(0f, tween(300, easing = exitEase))
            }
            else -> layer.shown.animateTo(0f, tween(280, easing = exitEase))
        }
    }

    private suspend fun cover(c: Cover, still: Boolean) = coroutineScope {
        if (still) {
            c.slip.snapTo(1f)
            c.dim.snapTo(1f)
        } else {
            launch { c.slip.animateTo(1f, tween(400, easing = screenEase)) }
            c.dim.animateTo(1f, tween(400, easing = Ease))
        }
    }

    private suspend fun uncover(c: Cover, still: Boolean, swiped: Boolean) = coroutineScope {
        when {
            still -> {
                c.slip.snapTo(0f)
                c.dim.snapTo(0f)
            }
            // Let go past the threshold: both finish the swipe together
            swiped -> {
                launch { c.slip.animateTo(0f, tween(240, easing = screenEase)) }
                c.dim.animateTo(0f, tween(240, easing = screenEase))
            }
            else -> {
                launch { c.slip.animateTo(0f, tween(400, easing = screenEase)) }
                c.dim.animateTo(0f, tween(400, easing = Ease))
            }
        }
    }
}

/** A grown screen's outline: the card's bounds at [progress] 0, the whole screen at 1, its [corner] easing to square. */
private class GrowShape(private val from: Rect, private val progress: Float, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(lerp(from, Rect(Offset.Zero, size), progress), CornerRadius(corner * (1f - progress))))
}

/**
 * One screen of the stack: canvas under it, moved, clipped or faded as its [layer] says, dimmed while covered, and the
 * shadow-float on its edge while it slides. [covered] (under another, or going) hides it from TalkBack.
 */
@Composable
private fun ScreenLayer(layer: Layer, covered: Boolean, content: @Composable () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val density = LocalDensity.current
    val corner = with(density) { Space.radiusMd.toPx() }
    val elevation = with(density) { (if (c.dark) 14.dp else 10.dp).toPx() }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val w = size.width
                val shown = layer.shown.value
                var x = layer.swipe.value * w
                if (!still && layer.entry == Entry.Slide) x += (1f - shown) * w
                if (!still && layer.cover.slides) x -= w / 4 * layer.cover.slip.value
                translationX = x
                alpha = if (still || layer.entry == Entry.Fade) shown.coerceIn(0f, 1f) else 1f
                val growing = !still && layer.entry == Entry.Grow && layer.from != null && shown < 1f
                clip = growing
                shape = if (growing) GrowShape(layer.from, shown, corner) else RectangleShape
                shadowElevation = if (!still && layer.entry == Entry.Slide && shown < 1f) elevation else 0f
                ambientShadowColor = Color.Black.copy(alpha = 0.3f)
                spotShadowColor = Color.Black.copy(alpha = if (c.dark) 0.7f else 0.18f)
            }
            .background(c.canvas)
            .pointerInput(Unit) {} // a tap on empty space must not reach the screen underneath
            .drawWithContent {
                drawContent()
                val dim = layer.cover.dim.value
                if (dim > 0f) drawRect(c.scrim, alpha = dim / 2)
            }
            .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { if (!still && layer.entry == Entry.Grow) alpha = layer.content.value.coerceIn(0f, 1f) }) { content() }
    }
}

/**
 * A memory playing as a story: grown out of the card it was played from ([from], 28dp corners, 440ms, the photo fading in
 * after 120ms), or, played from anywhere else, fading in over 200ms. Closing removes it at once, as in the design.
 */
@Composable
private fun StoryLayer(from: Rect?, content: @Composable () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val corner = with(LocalDensity.current) { Space.radiusLg.toPx() }
    val grow = remember { Animatable(if (still) 1f else 0f) }
    val fade = remember { Animatable(if (still || from == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (from != null) launch { fade.animateTo(1f, tween(300, delayMillis = 120, easing = Ease)) }
        grow.animateTo(1f, if (from != null) tween(440, easing = screenEase) else tween(200, easing = Ease))
    }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val p = grow.value
                if (from != null) {
                    clip = p < 1f
                    shape = if (p < 1f) GrowShape(from, p, corner) else RectangleShape
                } else alpha = p
            }
            .background(c.void),
    ) { Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) { content() } }
}

/** The three tabs, switched with the tab motion picked in Settings > Motion (Depth by default). The Design lab plays it on mock pages. */
@Composable
internal fun Tabs(tab: Tab, spec: AlbumSpec, revealFrom: Offset, onTab: (Tab) -> Unit, page: @Composable (Tab) -> Unit) {
    val motion = spec.appearance.tabMotion
    val settle = with(LocalDensity.current) { 10.dp.roundToPx() } // Instant: the new content settles from 10dp lower
    if (motion == TabMotion.Pager && !spec.still) {
        // Whole pages that also follow a sideways swipe: a tap slides them in 380ms, a swipe turns past 30% of the width
        // (or a flick) and settles in 300ms
        val pager = rememberPagerState(initialPage = tab.ordinal) { Tab.entries.size }
        LaunchedEffect(tab) { if (pager.currentPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal, animationSpec = tween(380, easing = screenEase)) }
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { onTab(Tab.entries[it]) } }
        HorizontalPager(
            pager, Modifier.fillMaxSize(), key = { it },
            flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = 0.3f, snapAnimationSpec = tween(300, easing = screenEase)),
        ) { page(Tab.entries[it]) }
        return
    }
    AnimatedContent(
        tab,
        Modifier.fillMaxSize(),
        transitionSpec = { tabTransition(motion, spec.still, targetState.ordinal - initialState.ordinal, settle) },
        label = "tabs",
    ) { t ->
        val effect = when {
            spec.still -> Modifier
            motion == TabMotion.Blur -> {
                // the old page dissolves out of focus, the new one sharpens in
                val radius by transition.animateDp({ tween(340, easing = enterEase) }, label = "blur") { if (it == EnterExitState.Visible) 0.dp else 18.dp }
                if (radius > 0.dp) Modifier.blur(radius) else Modifier
            }
            motion == TabMotion.Reveal -> {
                // the new page grows out of the tapped tab as a circle
                val grow by transition.animateFloat({ tween(480, easing = screenEase) }, label = "reveal") { if (it == EnterExitState.PreEnter) 0f else 1f }
                if (grow < 1f && transition.targetState == EnterExitState.Visible) Modifier.clip(RevealShape(revealFrom, grow)) else Modifier
            }
            else -> Modifier
        }
        Box(Modifier.fillMaxSize().then(effect)) { page(t) }
    }
}

private fun tabTransition(motion: TabMotion, still: Boolean, dir: Int, settle: Int): ContentTransform = when {
    still -> fadeIn(tween(120)) togetherWith fadeOut(tween(100))
    // The new page settles in from slightly larger; the old one recedes
    motion == TabMotion.Depth -> (fadeIn(tween(360, 40, enterEase)) + scaleIn(tween(360, 40, enterEase), initialScale = 1.06f)) togetherWith
        (fadeOut(tween(200, easing = exitEase)) + scaleOut(tween(200, easing = exitEase), targetScale = 0.94f))
    motion == TabMotion.Blur -> (fadeIn(tween(340, 30, enterEase)) + scaleIn(tween(340, 30, enterEase), initialScale = 1.02f)) togetherWith
        fadeOut(tween(200, easing = FastOutLinearInEasing))
    // No transition between pages, only a short settle of the new content
    motion == TabMotion.Instant -> (fadeIn(tween(240, easing = enterEase), initialAlpha = 0.5f) + slideInVertically(tween(240, easing = enterEase)) { settle }) togetherWith
        fadeOut(snap())
    motion == TabMotion.Reveal -> EnterTransition.None togetherWith
        (fadeOut(tween(480), targetAlpha = 0.6f) + scaleOut(tween(480), targetScale = 0.96f))
    // Slide: content moves the way the bottom-bar pill moved, 8% of the width
    else -> (slideInHorizontally(tween(300, 40, enterEase)) { it * dir / 12 } + fadeIn(tween(300, 40, enterEase))) togetherWith
        (slideOutHorizontally(tween(160, easing = exitEase)) { -it * dir / 12 } + fadeOut(tween(160, easing = exitEase)))
}

/** A circle around [center] that covers the whole screen at [progress] 1, starting from a 24dp disc at 0. */
private class RevealShape(private val center: Offset, private val progress: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val from = with(density) { 24.dp.toPx() }
        val c = if (center.isSpecified) center else Offset(size.width / 2, size.height)
        val far = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
            .maxOf { hypot(it.x - c.x, it.y - c.y) }
        val r = from + (far - from) * progress
        return Outline.Generic(Path().apply { addOval(Rect(c, r)) })
    }
}

// ---------- Pages ----------

@Composable
private fun LibraryPage(
    photos: List<Media>,
    loaded: Boolean,
    columns: androidx.compose.runtime.MutableIntState,
    grid: LazyGridState,
    selected: Set<Long>,
    onOpen: (Media) -> Unit,
    onSelect: (Set<Long>) -> Unit,
    padding: PaddingValues,
    gridPosition: Modifier,
    jumpDay: Long?,
    onJumped: () -> Unit,
    selectMode: Boolean,
    partial: Boolean,
    onAllowAll: () -> Unit,
    onStartSelecting: () -> Unit,
    onCalendar: () -> Unit,
    onMore: () -> Unit,
) {
    val videos = remember(photos) { photos.count { it.isVideo } }
    val subtitle = "${countOf(photos.size - videos, "photo")} · ${countOf(videos, "video")}"
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (photos.isNotEmpty()) {
            AlbumIconButton(Sym.CheckCircle, "Select", onStartSelecting)
            AlbumIconButton(Sym.CalendarMonth, "Jump to a date", onCalendar)
        }
        AlbumIconButton(Sym.MoreVert, "More", onMore)
    }
    if (loaded && photos.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            LargeTopBar("Library", actions = actions)
            if (partial) PartialAccessBanner(onAllowAll)
            EmptyState(Sym.PhotoLibrary, "No photos or videos yet", "Photos you take or save appear here.")
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        MediaGrid(
            photos, columns, grid, selected, onOpen, onSelect, padding, gridPosition,
            jumpTo = jumpDay,
            onJumped = onJumped,
            selectMode = selectMode,
            header = {
                Column {
                    LargeTopBar("Library", subtitle = subtitle, actions = actions)
                    IndexingCard(Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3))
                    if (partial) PartialAccessBanner(onAllowAll)
                }
            },
            bar = {
                // the title bar shows once the large title is 70dp up; derived, so scrolling recomposes only then
                val collapsed by rememberCollapsed(grid)
                CollapsingBar(collapsed, Modifier.statusBarsPadding()) {
                    CollapsedTopBar("Library", subtitle = subtitle, actions = actions)
                }
            },
        )
    }
}

/**
 * An album, special album, collection, place, thing or person, over the tabs. Slideshow plays its photos as a story
 * through [onPlay]; a folder the phone fills (Camera, Screenshots, an app's) shows where it lives under its title.
 */
@Composable
private fun AlbumPage(
    route: Route.Open,
    media: List<Media>,
    albums: List<Album>,
    collections: List<CollectionAlbum>,
    loaded: Boolean,
    columns: androidx.compose.runtime.MutableIntState,
    grid: LazyGridState,
    selected: Set<Long>,
    onOpen: (Media) -> Unit,
    onSelect: (Set<Long>) -> Unit,
    padding: PaddingValues,
    gridPosition: Modifier,
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    selectMode: Boolean,
    onStartSelecting: () -> Unit,
    report: (PageInfo) -> Unit,
    albumActions: AlbumActions,
    onPlay: (title: String, eyebrow: String, items: List<Media>) -> Unit,
) {
    val id = route.id
    val album = albums.find { it.id == id }
    val special = Special.entries.find { it.id == id }
    val collection = collections.find { it.albumId == id }
    val smart = rememberSmartAlbum(id) ?: rememberGroupPhotos(id, albums, collections, media)
    val person = rememberPerson(id)
    val items = remember(media, id, collection, smart, person) {
        collection?.items ?: smart?.items ?: person?.items ?: media.filter { it.isIn(id) }
    }
    val title = album?.name ?: special?.title ?: collection?.name ?: smart?.title ?: person?.let(::personTitle).orEmpty()
    DisposableEffect(route.key, items, collection, person, title) {
        report(PageInfo(route.key, items, collection, person, title.ifEmpty { null }))
        onDispose {}
    }
    // An album that stops existing (merged, emptied, deleted) closes after a moment for its data to load, instead of
    // lingering as an id that could reopen something else later
    val exists = album != null || special != null || collection != null || smart != null || person != null
    LaunchedEffect(exists) {
        if (!exists) {
            delay(1_500)
            onBack()
        }
    }
    val subtitle = album?.let { systemPath(it.name, it.cover.path) } ?: countOf(items.size, "item")
    var options by rememberSaveable { mutableStateOf(false) }
    if (options) AlbumOptionsSheet(id, albums, collections, media, albumActions) { options = false }
    // Stories play photos, oldest first, as a group's Play as a memory does
    val photos = remember(items) { items.filter { !it.isVideo } }
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (items.isNotEmpty()) AlbumIconButton(Sym.CheckCircle, "Select", onStartSelecting)
        if (photos.isNotEmpty()) AlbumIconButton(Sym.Slideshow, "Slideshow", { onPlay(title, countOf(items.size, "item"), spread(photos.asReversed(), STORY_LENGTH)) })
        // Album options holds Share as ZIP; pages without options keep it here
        if (hasAlbumOptions(id, album, collection)) AlbumIconButton(Sym.MoreVert, "Album options", { options = true })
        else if (items.isNotEmpty()) AlbumIconButton(Sym.Share, "Share as ZIP", { albumActions.shareZip(title, items) })
    }
    if (loaded && items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            LargeTopBar(title, onBack = onBack, actions = actions)
            EmptyState(Sym.PhotoLibrary, "Nothing here yet", special?.emptyText ?: smart?.emptyText ?: EMPTY_COLLECTION)
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        MediaGrid(
            items, columns, grid, selected, onOpen, onSelect, padding, gridPosition,
            selectMode = selectMode,
            header = {
                if (person != null) PersonHeader(person, onBack, onOpenPerson)
                else LargeTopBar(title, subtitle = subtitle, onBack = onBack, actions = actions)
            },
            bar = {
                val collapsed by rememberCollapsed(grid)
                CollapsingBar(collapsed, Modifier.statusBarsPadding()) {
                    CollapsedTopBar(title, subtitle = subtitle, onBack = onBack, actions = actions)
                }
            },
        )
    }
}

/** The selection bar's actions for the screen on show: in a collection Remove replaces Delete; on a person, Not them. */
@Composable
private fun SelectionActions(
    selected: Set<Long>,
    shown: List<Media>,
    collection: CollectionAlbum?,
    person: PersonAlbum?,
    context: Context,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onAlbum: (List<Media>) -> Unit,
    onFavourite: (List<Media>) -> Unit,
    onRemove: (CollectionAlbum, List<Long>) -> Unit,
    onNotPerson: (PersonAlbum, List<Long>) -> Unit,
    onTrash: (List<Media>) -> Unit,
    onMore: (List<Media>) -> Unit,
) {
    // Read at tap time, not on every selection change. With nothing picked yet (Select), the actions wait.
    val items = { shown.filter { it.id in selected } }
    fun act(label: String, sym: Sym, danger: Boolean = false, run: () -> Unit) = SelAction(sym, label, danger) { if (selected.isNotEmpty()) run() }
    val actions = buildList {
        add(act("Share", Sym.Share) {
            val list = items()
            if (context.canSend(list.size, MAX_SHARE, "share")) {
                CrashLog.note("share ${list.size}")
                shareMedia(context, list)
            }
        })
        if (collection != null) add(act("Remove", Sym.RemoveCircle) { onRemove(collection, items().map { it.id }) })
        else add(act("Album", Sym.Add) { onAlbum(items()) })
        add(act("Favourite", Sym.Favorite) { onFavourite(items()) })
        if (person != null) add(act("Not them", Sym.PersonRemove) { onNotPerson(person, items().map { it.id }) })
        if (collection == null) add(act("Delete", Sym.Delete, danger = true) { onTrash(items()) })
        add(act("More", Sym.MoreHoriz) { onMore(items()) }) // Share as ZIP, Edit date and time, Edit place
    }
    SelectionBar(
        selected.size, actions, onClear,
        onSelectAll = onSelectAll,
        allLabel = if (shown.isNotEmpty() && shown.all { it.id in selected }) "Clear all" else "Select all",
    )
}

@Composable
private fun LibraryMenu(columns: Int, onDismiss: () -> Unit, onCalendar: () -> Unit, onAppearance: () -> Unit, onGallery: () -> Unit, onSettings: () -> Unit) {
    val a = AlbumTheme.spec.appearance
    Sheet(onDismiss = onDismiss, title = "Library") {
        MenuRow(Sym.CalendarMonth, "Jump to a date", onCalendar, sub = "Calendar with your photos on each day")
        MenuRow(Sym.GridView, "Gallery style", onGallery, sub = "${a.gallery.label} · $columns columns")
        MenuRow(Sym.Palette, "Appearance", onAppearance, sub = "${a.theme.label} · ${a.accent.label}")
        MenuRow(Sym.Settings, "Settings", onSettings)
    }
}

/** Shown on the Library until "Got it": how to open, select and zoom, on a surface card above the bar. */
@Composable
private fun FirstRunTip(onDone: () -> Unit) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusLg)
    Row(
        Modifier.fillMaxWidth().floating(shape).background(c.surface, shape)
            .padding(start = Space.s4, top = Space.s3, end = Space.s2, bottom = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.TouchApp, null, tint = c.ink)
        Text(
            "Tap a photo to open it. Long-press to select, then drag across more. Pinch to change columns.",
            Modifier.weight(1f), style = AlbumTheme.type.body, color = c.ink,
        )
        AlbumButton("Got it", onDone, variant = ButtonVariant.Quiet)
    }
}

/** A calm note at the top of the Library: Album only sees the photos picked in Android 14's "Select photos". */
@Composable
private fun PartialAccessBanner(onAllowAll: () -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s1)
            .background(c.surfaceHigh, RoundedCornerShape(Space.radiusMd)).padding(start = Space.s4, end = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Album can only see the photos you picked.", Modifier.weight(1f), style = AlbumTheme.type.body, color = c.ink)
        AlbumButton("Allow all", onAllowAll, variant = ButtonVariant.Quiet)
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(Space.s8), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LogoMark(size = 56.dp)
        EmptyState(
            Sym.PhotoLibrary,
            "See your photos here",
            "Album needs access to your photos and videos. Everything stays on this phone.",
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                AlbumButton("Allow access", onAllow, variant = ButtonVariant.Primary)
                // If you tapped "Don't allow" twice, Android stops asking; this opens the app's settings page instead.
                AlbumButton("Open settings", {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }, variant = ButtonVariant.Quiet)
            }
        }
    }
}

/** "1 item", "1,234 photos": the count grouped the way the phone's locale writes numbers. */
fun countOf(n: Int, noun: String) = "${NumberFormat.getIntegerInstance().format(n)} $noun${if (n == 1) "" else "s"}"
