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
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.SnackbarHost
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
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AlbumPrefs.load(this)
        AppearancePrefs.load(this)
        setContent {
            val appearance by AppearancePrefs.appearance.collectAsState()
            // Status bar icons follow Album's theme, not the phone's: a Light theme on a dark phone still needs dark icons
            val dark = resolvedTheme(appearance.theme) != ThemeMode.Light
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            AlbumTheme(appearance) { AlbumShell() }
        }
    }

    override fun onStop() {
        super.onStop()
        // The home-screen icon's sun follows the accent; switching it closes this task, so only once Album is out of sight
        if (!isChangingConfigurations) LauncherIcon.sync(this, AppearancePrefs.appearance.value.accent)
    }
}

private enum class Tab(val sym: Sym, val label: String) {
    Library(Sym.PhotoLibrary, "Library"),
    Collections(Sym.CollectionsBookmark, "Collections"),
    ForYou(Sym.Favorite, "For you"),
}

/** A screen opened over the tabs. Deeper screens slide in from the right and Back pops them in order. */
private sealed class Route(val key: String) {
    /** An album: a folder, Favourites or Videos, a collection, a place, a thing or a person. */
    data class Open(val id: Long) : Route("open:$id")
    data object Albums : Route("albums")
    data object Places : Route("places")
    data object Storage : Route("storage")
    data object PrivateSafe : Route("safe") // not "Safe": that's the object in Safe.kt

    /** Clean up, on one category (a CleanupKind name) or the suggested ones. */
    data class Clean(val category: String?) : Route("cleanup:${category.orEmpty()}")
    data object People : Route("people")
    data object PeopleReview : Route("people-review")
    data object Trash : Route("trash")
    data object Search : Route("search")
    data object Settings : Route("settings")

    // Pages pushed from Settings
    data object Describe : Route("describe")
    data object Updates : Route("updates")
    data object Diagnostics : Route("diagnostics")
    data object About : Route("about")
    data object Licences : Route("licences")

    companion object {
        fun of(key: String): Route? = key.removePrefix("open:").toLongOrNull()?.takeIf { key.startsWith("open:") }?.let(::Open)
            ?: key.removePrefix("cleanup:").takeIf { key.startsWith("cleanup:") }?.let { Clean(it.ifEmpty { null }) }
            ?: listOf(Albums, Places, People, PeopleReview, Trash, Search, Settings, Storage, PrivateSafe, Describe, Updates, Diagnostics, About, Licences)
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

/** What an open album page shows, reported up so the viewer and the selection bar work on the same photos. */
private class PageInfo(val key: String, val items: List<Media>, val collection: CollectionAlbum?, val person: PersonAlbum?)

/** A memory or album playing as a story. */
private class Story(val items: List<Media>, val title: String?, val eyebrow: String?)

@Composable
private fun AlbumShell() {
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
    var pushing by remember { mutableStateOf(true) } // direction of the last stack change, for the slide and the dim
    val push = { r: Route ->
        // A second tap on the same card during the slide (the screen beneath still shows part way) opens nothing new
        if (stack.lastOrNull() != r) {
            pushing = true
            stack.add(r)
            CrashLog.note("open ${r.key.substringBefore(':')}")
        }
    }
    val pop = {
        pushing = false
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
    var addingToCollection by remember { mutableStateOf<List<Long>?>(null) }
    var story by remember { mutableStateOf<Story?>(null) }
    var protecting by remember { mutableStateOf<List<Media>?>(null) } // For you's ID-card photos, while asking
    var barHidden by remember { mutableStateOf(false) }

    val loaded = Gallery.media.collectAsState().value
    val media = loaded.orEmpty()
    val hidden by AlbumPrefs.hidden.collectAsState()
    val photos = remember(media, hidden) { media.inPhotos(hidden) } // the Library leaves out folders hidden from it
    val trash = if (access != Access.None) rememberTrash().value else null
    val albums = remember(media) { albumsOf(media) }
    val specials = remember(media, trash) { specialCounts(media, trash.orEmpty().size) }
    val collections = rememberCollections(media)

    // Pages report what they show, so the viewer and the selection bar act on the photos on screen
    var page by remember { mutableStateOf<PageInfo?>(null) }
    var searchState by remember { mutableStateOf<SearchState?>(null) }
    val shown = when (top) {
        is Route.Open -> page?.takeIf { it.key == top.key }?.items.orEmpty()
        Route.Search -> searchState?.items.orEmpty()
        // Clean up and Storage show items from every folder (WhatsApp may be hidden from the Library)
        is Route.Clean, Route.Storage -> media
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
    val trashWithUndo = rememberTrashWithUndo(snackbar) { clearSelection() }
    // Always composed: it also finishes a move into the safe that the app was closed in the middle of.
    // The selection clears only once the fingerprint is given, so a cancelled prompt leaves it as it was.
    val moveToSafe = rememberMoveToSafe(snackbar) { clearSelection() }
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
    }
    val startSelecting = {
        selectMode = true
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
    BackHandler(top != null && !selecting) { pop() }

    // The bottom bar sinks on a fast scroll down and comes back on any scroll up
    val barOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -24f) barHidden = true else if (available.y > 4f) barHidden = false
                return Offset.Zero
            }
        }
    }

    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Scrolling content clears the floating bar: bar height plus space-8, above the gesture area
    val tabPadding = PaddingValues(top = statusBar, bottom = navBar + Space.s4 + Space.bar + Space.s8)
    var revealFrom by remember { mutableStateOf(Offset.Unspecified) }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        if (access == Access.None) {
            PermissionScreen { askAccess.launch(mediaPermissions) }
            return@Box
        }

        @Composable
        fun TabPage(t: Tab) {
            Box(Modifier.fillMaxSize().background(c.canvas).nestedScroll(barOnScroll)) {
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
                        albums, specials, collections, collectionsGrid, tabPadding,
                        onOpen = { id ->
                            CrashLog.note("open album ${Special.entries.find { it.id == id }?.name ?: if (collectionIdOf(id) != null) "collection" else "other"}")
                            openId(id)
                        },
                        onAllAlbums = { push(Route.Albums) },
                        onAllPlaces = { push(Route.Places) },
                        onAllPeople = { push(Route.People) },
                        onReviewPeople = { push(Route.PeopleReview) },
                        onOpenSafe = { push(Route.PrivateSafe) },
                        onAction = albumActions::onAlbum,
                        onNewAlbum = albumActions::newAlbum,
                        onSettings = { push(Route.Settings) },
                    )
                    Tab.ForYou -> ForYouScreen(
                        photos, forYouList, tabPadding,
                        cleanupCount = cleanup?.toReview ?: 0,
                        onPlay = { title, eyebrow, items ->
                            CrashLog.note("play memory")
                            story = Story(items, title, eyebrow)
                        },
                        onCleanup = { push(Route.Clean(null)) },
                        onStorage = { push(Route.Storage) },
                        // A false positive must not leave the Library on one tap: confirm with what will move
                        onProtect = { items -> protecting = items },
                        onCleanupCategory = { push(Route.Clean(it)) },
                    )
                }
            }
        }

        // The tabs stay composed under deeper screens, so Back to them is instant: no grid layout rebuilt, the scroll where
        // it was. While covered they slip 25% left and dim under the scrim, and TalkBack skips them.
        val covered = top != null
        val under by animateFloatAsState(
            if (covered) 1f else 0f,
            if (spec.still) snap() else tween(if (covered) 400 else 280, easing = screenEase),
            label = "under",
        )
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationX = -size.width / 4 * under }
                .drawWithContent {
                    drawContent()
                    if (under > 0f) drawRect(c.scrim, alpha = under / 2)
                }
                .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier),
        ) { Tabs(tab, spec, revealFrom, onTab = { tab = it }) { TabPage(it) } }

        // Deeper screens slide over the tabs and over each other
        AnimatedContent(
            top,
            Modifier.fillMaxSize(),
            transitionSpec = { stackTransition(spec.still, pushing, initialState, targetState) },
            contentKey = { it?.key },
            label = "screens",
        ) { route ->
            val dim by transition.animateFloat(label = "dim") { s ->
                // the screen beneath a pushed one dims under the scrim, at half strength
                when (s) {
                    EnterExitState.Visible -> 0f
                    EnterExitState.PreEnter -> if (pushing || spec.still) 0f else 0.5f
                    EnterExitState.PostExit -> if (pushing && !spec.still) 0.5f else 0f
                }
            }
            if (route == null) return@AnimatedContent // the tabs underneath show through
            // A closed screen opens fresh next time: its state goes once it has left, not while it slides out (Back and
            // forward again inside the slide brings back this same composition, which must keep saving)
            DisposableEffect(route.key) {
                onDispose { if (stack.none { it.key == route.key }) routeStates.removeState(route.key) }
            }
            Box(
                Modifier.fillMaxSize()
                    .background(c.canvas)
                    .pointerInput(Unit) {} // a tap on empty space must not reach the tabs underneath
                    .drawWithContent {
                        drawContent()
                        if (dim > 0f) drawRect(c.scrim, alpha = dim)
                    },
            ) {
                routeStates.SaveableStateProvider(route.key) {
                    when (route) {
                        is Route.Open -> AlbumPage(
                            route, media, albums, collections, loaded != null, albumColumns,
                            albumGrids.getOrPut(route.key) { LazyGridState() }, selected, open, select,
                            tabPadding, gridPosition,
                            onBack = pop,
                            onOpenPerson = { id -> stack[stack.lastIndex] = Route.Open(id) }, // merging leaves you on the merged person
                            selectMode = selectMode,
                            onStartSelecting = startSelecting,
                            report = { page = it },
                        )
                        Route.Albums -> AllAlbumsScreen(
                            albums, collections, albumsGrid, onBack = pop, onOpen = openId,
                            onAction = albumActions::onAlbum, onNewAlbum = albumActions::newAlbum,
                        )
                        Route.Places -> PlacesScreen(placesGrid, onBack = pop, onOpen = openId)
                        // Clean up and Storage get every item: WhatsApp may live in folders hidden from the Library
                        is Route.Clean -> CleanupScreen(media, onBack = pop, onOpen = open, category = route.category)
                        Route.Storage -> StorageScreen(media, onBack = pop, onOpenCategory = { push(Route.Clean(it)) }, onOpen = open)
                        Route.PrivateSafe -> SafeScreen(onBack = pop)
                        Route.People -> PeopleScreen(peopleGrid, onBack = pop, onOpenPerson = openId, onReviewPeople = { push(Route.PeopleReview) })
                        Route.PeopleReview -> PeopleReviewScreen(onBack = pop)
                        Route.Trash -> TrashScreen(trash, onBack = pop)
                        Route.Settings -> SettingsScreen(
                            onBack = pop, columns = photosColumns, onOpenTrash = { push(Route.Trash) }, onDescribe = { push(Route.Describe) },
                            onUpdates = { push(Route.Updates) }, onDiagnostics = { push(Route.Diagnostics) }, onAbout = { push(Route.About) },
                        )
                        Route.Describe -> DescribeScreen(onBack = pop)
                        Route.Updates -> UpdatesScreen(onBack = pop)
                        Route.Diagnostics -> DiagnosticsScreen(onBack = pop)
                        Route.About -> AboutScreen(onBack = pop, onLicences = { push(Route.Licences) })
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
        }

        // The status bar always sits on canvas, so photos never scroll under its icons
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.canvas))

        // Bottom chrome: the selection bar replaces the bottom bar in place; deeper screens let it sink away
        val barShown = top == null && !selecting && !barHidden
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4)) {
            AnimatedVisibility(barShown, enter = rise(spec.still), exit = sink(spec.still)) {
                BottomBar(
                    Tab.entries.map { NavItem(it.sym, it.label) },
                    tab.ordinal,
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
                )
            }
            AnimatedVisibility(selecting, enter = rise(spec.still), exit = sink(spec.still)) {
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
                )
            }
        }
        // Snackbars rise just above whatever bar is at the bottom
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (barShown || top == Route.Search) Space.bar + Space.s3 else if (selecting) Space.bar * 2 + Space.s4 else 0.dp),
        ) { AlbumSnackbar(it) }

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
            }
        }
        protecting?.let { items ->
            ConfirmBar(
                title = "Move ${countOf(items.size, "photo")} to Private safe?",
                body = "They look like ID cards. They leave the Library and search, and open only with your fingerprint or screen lock.",
                confirmLabel = "Move to safe",
                onConfirm = {
                    protecting = null
                    moveToSafe(items)
                },
                onCancel = { protecting = null },
                icon = Sym.Lock,
                tone = ConfirmTone.Flash,
                thumbs = items.take(3),
            )
        }
        addingToCollection?.let { ids ->
            AddToCollectionSheet(ids.size, collections, onDismiss = { addingToCollection = null }, onPick = { id, name ->
                addToCollection(id, name, ids)
                addingToCollection = null
                clearSelection()
            })
        }
        story?.let { s ->
            Slideshow(s.items, s.items.first().id, onClose = { story = null }, title = s.title, eyebrow = s.eyebrow)
        }
        // A recreated activity (rotation, dark mode, split screen) restores viewerId before the open album or search has
        // reported its photos; waiting for them keeps the viewer from closing itself on an empty list
        val shownReady = when (top) {
            is Route.Open -> page?.key == top.key
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
            )
            // The main snackbar sits under the viewer, so the viewer gets its own host for Undo
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = viewerSnackbarGap)) { AlbumSnackbar(it) }
        }
    }
}

// ---------- Motion ----------

private val enterEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // tab content settling in
private val exitEase = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // anything leaving: quick to get out of the way
private val screenEase = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f) // screens and sheets arriving

/** Deeper screens slide over from the right (the one beneath slips 25% left and dims), Back reverses it; search fades. */
private fun AnimatedContentTransitionScope<Route?>.stackTransition(still: Boolean, pushing: Boolean, from: Route?, to: Route?): ContentTransform = when {
    still -> fadeIn(tween(120)) togetherWith fadeOut(tween(100))
    from == Route.Search || to == Route.Search -> fadeIn(tween(220, easing = screenEase)) togetherWith fadeOut(tween(150))
    pushing -> slideInHorizontally(tween(400, easing = screenEase)) { it } togetherWith
        slideOutHorizontally(tween(400, easing = screenEase)) { -it / 4 }
    else -> (slideInHorizontally(tween(280, easing = screenEase)) { -it / 4 } togetherWith
        slideOutHorizontally(tween(280, easing = exitEase)) { it }).apply { targetContentZIndex = -1f } // the leaving screen stays on top
}.using(SizeTransform(clip = false))

private fun rise(still: Boolean) = if (still) fadeIn(tween(120)) else slideInVertically(tween(300, easing = screenEase)) { it } + fadeIn(tween(150))
private fun sink(still: Boolean) = if (still) fadeOut(tween(100)) else slideOutVertically(tween(220, easing = exitEase)) { it } + fadeOut(tween(220))

/** The three tabs, switched with the tab motion picked in Settings > Motion (Depth by default). */
@Composable
private fun Tabs(tab: Tab, spec: AlbumSpec, revealFrom: Offset, onTab: (Tab) -> Unit, page: @Composable (Tab) -> Unit) {
    val motion = spec.appearance.tabMotion
    if (motion == TabMotion.Pager && !spec.still) {
        // Whole pages that also follow a sideways swipe
        val pager = rememberPagerState(initialPage = tab.ordinal) { Tab.entries.size }
        LaunchedEffect(tab) { if (pager.currentPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal) }
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { onTab(Tab.entries[it]) } }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page(Tab.entries[it]) }
        return
    }
    AnimatedContent(
        tab,
        Modifier.fillMaxSize(),
        transitionSpec = { tabTransition(motion, spec.still, targetState.ordinal - initialState.ordinal) },
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

private fun tabTransition(motion: TabMotion, still: Boolean, dir: Int): ContentTransform = when {
    still -> fadeIn(tween(120)) togetherWith fadeOut(tween(100))
    // The new page settles in from slightly larger; the old one recedes
    motion == TabMotion.Depth -> (fadeIn(tween(360, 40, enterEase)) + scaleIn(tween(360, 40, enterEase), initialScale = 1.06f)) togetherWith
        (fadeOut(tween(200, easing = exitEase)) + scaleOut(tween(200, easing = exitEase), targetScale = 0.94f))
    motion == TabMotion.Blur -> (fadeIn(tween(340, 30, enterEase)) + scaleIn(tween(340, 30, enterEase), initialScale = 1.02f)) togetherWith
        fadeOut(tween(200, easing = FastOutLinearInEasing))
    // No transition between pages, only a short settle of the new content
    motion == TabMotion.Instant -> (fadeIn(tween(240, easing = enterEase), initialAlpha = 0.5f) + slideInVertically(tween(240, easing = enterEase)) { 28 }) togetherWith
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
        )
        // derived, so scrolling recomposes only when the title scrolls in or out, never per row
        val collapsed by remember(grid) { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
        CollapsingBar(collapsed, Modifier.statusBarsPadding()) {
            CollapsedTopBar("Library", subtitle = subtitle, actions = actions)
        }
    }
}

/** An album, special album, collection, place, thing or person, over the tabs. */
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
) {
    val id = route.id
    val album = albums.find { it.id == id }
    val special = Special.entries.find { it.id == id }
    val collection = collections.find { it.albumId == id }
    val smart = rememberSmartAlbum(id)
    val person = rememberPerson(id)
    val items = remember(media, id, collection, smart, person) {
        collection?.items ?: smart?.items ?: person?.items ?: media.filter { it.isIn(id) }
    }
    DisposableEffect(route.key, items, collection, person) {
        report(PageInfo(route.key, items, collection, person))
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
    val title = album?.name ?: special?.title ?: collection?.name ?: smart?.title ?: person?.let(::personTitle).orEmpty()
    val subtitle = countOf(items.size, "item")
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (items.isNotEmpty()) {
            AlbumIconButton(Sym.CheckCircle, "Select", onStartSelecting)
        }
    }
    if (loaded && items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            LargeTopBar(title, onBack = onBack)
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
        )
        val collapsed by remember(grid) { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
        CollapsingBar(collapsed, Modifier.statusBarsPadding()) {
            CollapsedTopBar(title, subtitle = subtitle, onBack = onBack, actions = actions)
        }
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
) {
    // Read at tap time, not on every selection change. With nothing picked yet (Select), the actions wait.
    val items = { shown.filter { it.id in selected } }
    fun act(label: String, sym: Sym, danger: Boolean = false, run: () -> Unit) = SelAction(sym, label, danger) { if (selected.isNotEmpty()) run() }
    val actions = buildList {
        add(act("Share", Sym.Share) {
            val list = items()
            if (context.canSend(list.size, MAX_SHARE, "share")) {
                CrashLog.note("share ${list.size}")
                context.startSafely(shareIntent(list))
            }
        })
        if (collection != null) add(act("Remove", Sym.RemoveCircle) { onRemove(collection, items().map { it.id }) })
        else add(act("Album", Sym.Add) { onAlbum(items()) })
        add(act("Favourite", Sym.Favorite) { onFavourite(items()) })
        if (person != null) add(act("Not them", Sym.PersonRemove) { onNotPerson(person, items().map { it.id }) })
        if (collection == null) add(act("Delete", Sym.Delete, danger = true) { onTrash(items()) })
    }
    SelectionBar(
        selected.size, actions, onClear,
        onSelectAll = onSelectAll,
        allLabel = if (shown.isNotEmpty() && shown.all { it.id in selected }) "Clear all" else "Select all",
    )
}

@Composable
private fun LibraryMenu(columns: Int, onDismiss: () -> Unit, onCalendar: () -> Unit, onSettings: () -> Unit) {
    val a = AlbumTheme.spec.appearance
    Sheet(onDismiss = onDismiss, title = "Library") {
        MenuRow(Sym.CalendarMonth, "Jump to a date", onCalendar, sub = "Calendar with your photos on each day")
        MenuRow(Sym.GridView, "Gallery style", onSettings, sub = "${a.gallery.label} · $columns columns")
        MenuRow(Sym.Palette, "Appearance", onSettings, sub = "Theme, accent and look")
        MenuRow(Sym.Settings, "Settings", onSettings)
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
