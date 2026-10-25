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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonRemove
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        AlbumPrefs.load(this)
        setContent {
            var look by remember { mutableStateOf(Look.entries.find { it.name == prefs.getString("look", null) } ?: Look.Clean) }
            AlbumTheme(look) {
                AlbumShell(look, onLook = {
                    look = it
                    prefs.edit { putString("look", it.name) }
                    CrashLog.note("look ${it.name}")
                })
            }
        }
    }
}

private enum class Tab { Photos, Albums, Explore }

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

@Composable
private fun AlbumShell(look: Look, onLook: (Look) -> Unit) {
    val context = LocalContext.current
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

    var tab by rememberSaveable { mutableStateOf(Tab.Photos) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var peopleOpen by rememberSaveable { mutableStateOf(false) } // Explore's "See all" people
    var albumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exploreId by rememberSaveable { mutableStateOf<Long?>(null) } // a place, thing or utility opened from Explore
    var allPlaces by rememberSaveable { mutableStateOf(false) } // Explore's "See all" places
    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    val loaded = Gallery.media.collectAsState().value
    val media = loaded.orEmpty()
    val hidden by AlbumPrefs.hidden.collectAsState()
    val photos = remember(media, hidden) { media.inPhotos(hidden) } // Photos leaves out folders hidden from it
    val trash = if (access != Access.None) rememberTrash().value else null
    val albums = remember(media) { albumsOf(media) }
    val specials = remember(media, trash) { specialCounts(media, trash.orEmpty().size) }
    val collections = rememberCollections(media)
    // Albums and Explore each keep their own open album, so switching tabs comes back to it
    val openId = when (tab) {
        Tab.Albums -> albumId
        Tab.Explore -> exploreId
        Tab.Photos -> null
    }
    val album = albums.find { it.id == openId }
    val special = Special.entries.find { it.id == openId }
    val collection = collections.find { it.albumId == openId }
    val smart = openId?.let { rememberSmartAlbum(it) }
    val person = rememberPerson(openId) // person pages open from Explore
    val albumMedia = remember(media, openId, collection, smart, person) {
        collection?.items ?: smart?.items ?: person?.items ?: openId?.let { id -> media.filter { it.isIn(id) } }.orEmpty()
    }
    val inTrash = special == Special.Trash
    val inAlbum = (album != null || special != null || collection != null || smart != null || person != null) && !inTrash
    val closeAlbum = { if (tab == Tab.Explore) exploreId = null else albumId = null }
    // Search replaces the top bar and the page underneath it, and its results are what selection and the viewer work on
    val search = if (searchOpen) rememberSearch(photos, collections) else null
    // Only while that page is what's on screen: search results get the normal selection actions, not Remove or Not <name>
    val openCollection = collection?.takeIf { inAlbum && search == null } // its selection removes from it instead of adding to one
    val openPerson = person?.takeIf { inAlbum && search == null }
    // An open album or person that stops existing (merged, emptied, deleted) closes after a moment for its data to load,
    // instead of lingering as an id that could reopen something else later
    LaunchedEffect(openId, inAlbum, inTrash) {
        if (openId != null && !inAlbum && !inTrash) {
            delay(1_500)
            closeAlbum()
        }
    }
    val shown = search?.items ?: if (inAlbum) albumMedia else photos

    // Grid states live here so scroll position and zoom survive the viewer, settings and tab switches.
    val photosGrid = rememberLazyGridState()
    val photosColumns = rememberColumns("columns_photos")
    val albumsGrid = rememberLazyGridState()
    val albumGrid = rememberLazyGridState()
    val exploreGrid = rememberLazyGridState()
    val placesGrid = rememberLazyGridState()
    val exploreAlbumGrid = rememberLazyGridState() // apart from albumGrid, so each tab's open album keeps its place
    val openGrid = if (tab == Tab.Explore) exploreAlbumGrid else albumGrid
    val openFromExplore = { id: Long ->
        exploreAlbumGrid.requestScrollToItem(0) // a new album starts at the top, not where the last one was left
        exploreId = id
    }
    val albumColumns = rememberColumns("columns_album")
    val searchGrid = rememberLazyGridState()
    val searchColumns = rememberColumns("columns_search")
    val peopleGrid = rememberLazyGridState()

    val snackbar = remember { SnackbarHostState() }
    val trashWithUndo = rememberTrashWithUndo(snackbar) { selected = emptySet() }
    val albumActions = rememberAlbumActions { selected = emptySet() }
    val addToCollection = rememberAddToCollection(snackbar)
    val removeFromCollection = rememberRemoveFromCollection(snackbar)
    val notPerson = rememberNotPerson(snackbar) { exploreId = it }
    var addingToCollection by remember { mutableStateOf<List<Long>?>(null) } // the selection, while the sheet is open
    // Photos deleted by another app leave the selection, so "N selected" and its actions stay truthful
    LaunchedEffect(shown) {
        val ids = shown.mapTo(HashSet()) { it.id }
        if (!ids.containsAll(selected)) selected = selected.filterTo(HashSet()) { it in ids }
    }
    val selecting = selected.isNotEmpty()
    val select = { ids: Set<Long> -> selected = ids }
    // Where the visible grid sits on screen, so a photo can fly out of its cell into the viewer and back into it
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var openedFrom by remember { mutableStateOf<Rect?>(null) }
    val gridPosition = Modifier.onGloballyPositioned { gridOrigin = it.positionInWindow() }
    val cellOnScreen = cell@{ id: Long ->
        // the grid that's on screen; none for screens without one (Explore's own page), so the viewer fades instead
        val grid = when {
            search != null -> searchGrid
            inAlbum -> openGrid
            tab == Tab.Photos -> photosGrid
            else -> return@cell null
        }
        val info = grid.layoutInfo
        info.visibleItemsInfo.find { it.key == id }?.let { cell ->
            // cell offsets start after the top content padding; viewportStartOffset is minus that padding
            Rect(gridOrigin + Offset(cell.offset.x.toFloat(), (cell.offset.y - info.viewportStartOffset).toFloat()), cell.size.toSize())
        }
    }
    val open = { m: Media ->
        openedFrom = cellOnScreen(m.id)
        CrashLog.note("open ${if (m.isVideo) "video" else "photo"}")
        viewerId = m.id
    }
    BackHandler(selecting) { selected = emptySet() }
    BackHandler(inAlbum && !selecting) { closeAlbum() }
    BackHandler(tab == Tab.Explore && allPlaces && !inAlbum) { allPlaces = false }
    BackHandler(search != null && !selecting) { searchOpen = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                    when {
                        selecting -> TitleBar("${selected.size} selected", backIcon = Icons.Outlined.Close, onBack = { selected = emptySet() }) {
                            val all = shown.all { it.id in selected }
                            IconButton(onClick = {
                                CrashLog.note(if (all) "clear all" else "select all")
                                selected = toggleAll(selected, shown.map { it.id })
                            }) { Icon(if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, if (all) "Clear all" else "Select all") }
                            IconButton(onClick = {
                                val items = shown.filter { it.id in selected } // at tap time, not on every selection change
                                if (context.canSend(items.size, MAX_SHARE, "share")) {
                                    CrashLog.note("share ${items.size}")
                                    context.startSafely(shareIntent(items))
                                }
                            }) { Icon(Icons.Outlined.Share, "Share") }
                            if (openPerson != null) IconButton(onClick = {
                                notPerson(openPerson, shown.filter { it.id in selected }.map { it.id })
                                selected = emptySet()
                            }) { Icon(Icons.Outlined.PersonRemove, notPersonLabel(openPerson)) }
                            if (openCollection != null) {
                                IconButton(onClick = {
                                    removeFromCollection(openCollection, shown.filter { it.id in selected }.map { it.id })
                                    selected = emptySet()
                                }) { Icon(Icons.Outlined.RemoveCircleOutline, "Remove from collection") }
                            } else {
                                // Less frequent actions share one menu so the bar stays readable on a phone
                                var more by remember { mutableStateOf(false) }
                                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                                DropdownMenu(more, onDismissRequest = { more = false }) {
                                    val pick = { action: () -> Unit -> more = false; action() }
                                    DropdownMenuItem(
                                        text = { Text("Move to album") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                                        onClick = { pick { albumActions.moveOrCopy(shown.filter { it.id in selected }, Transfer.Move) } },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy to album") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                        onClick = { pick { albumActions.moveOrCopy(shown.filter { it.id in selected }, Transfer.Copy) } },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to collection") },
                                        leadingIcon = { Icon(Icons.Outlined.LibraryAdd, null) },
                                        onClick = { pick { addingToCollection = shown.filter { it.id in selected }.map { it.id } } },
                                    )
                                }
                            }
                            if (openCollection == null) IconButton(onClick = {
                                val items = shown.filter { it.id in selected }
                                CrashLog.note("trash ${items.size}")
                                trashWithUndo(items)
                            }) { Icon(Icons.Outlined.Delete, "Delete") }
                        }
                        search != null -> SearchBar(search, onBack = { searchOpen = false })
                        openPerson != null -> PersonTitleBar(openPerson, onBack = closeAlbum) { exploreId = it } // merging opens the merged person
                        inAlbum -> TitleBar(album?.name ?: special?.title ?: collection?.name ?: smart?.title.orEmpty(), onBack = closeAlbum)
                        tab == Tab.Explore && allPlaces -> TitleBar("Places", onBack = { allPlaces = false })
                        else -> TitleBar(tab.name) {
                            val icons = LocalLook.current.icons
                            if (tab == Tab.Photos && photos.isNotEmpty()) {
                                IconButton(onClick = {
                                    calendarOpen = true
                                    CrashLog.note("open calendar")
                                }) { Icon(icons.calendar, "Calendar") }
                            }
                            if (tab == Tab.Albums) AlbumSortButton()
                            IconButton(onClick = {
                                searchOpen = true
                                CrashLog.note("open search")
                            }) { Icon(icons.search, "Search") }
                            IconButton(onClick = {
                                settingsOpen = true
                                CrashLog.note("open settings")
                            }) { Icon(icons.settings, "Settings") }
                        }
                    }
                    if (access == Access.Partial && !selecting) PartialAccessBanner { askAccess.launch(mediaPermissions) }
                }
            },
            bottomBar = {
                if (!selecting && search == null) {
                    val icons = LocalLook.current.icons
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = {
                                    if (tab == t && t == Tab.Albums) albumId = null // tapping Albums again goes back to the list
                                    if (tab == t && t == Tab.Explore) {
                                        exploreId = null
                                        allPlaces = false
                                    }
                                    tab = t
                                    CrashLog.note("tab ${t.name}")
                                },
                                icon = {
                                    Icon(
                                        when (t) {
                                            Tab.Photos -> icons.photos
                                            Tab.Albums -> icons.albums
                                            Tab.Explore -> icons.explore
                                        },
                                        null,
                                    )
                                },
                                label = { Text(t.name) },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when {
                access == Access.None -> PermissionScreen(Modifier.padding(padding)) { askAccess.launch(mediaPermissions) }
                search != null -> SearchContent(search, searchColumns, searchGrid, selected, open, select, padding, gridPosition)
                // Albums still opens when empty, so Recently deleted stays reachable after deleting everything
                loaded != null && media.isEmpty() && tab != Tab.Albums -> Message("No photos or videos yet", Modifier.padding(padding))
                tab == Tab.Photos -> MediaGrid(photos, photosColumns, photosGrid, selected, open, select, padding, gridPosition)
                inAlbum && loaded != null && albumMedia.isEmpty() -> Message(special?.emptyText ?: smart?.emptyText ?: EMPTY_COLLECTION, Modifier.padding(padding))
                inAlbum -> MediaGrid(albumMedia, albumColumns, openGrid, selected, open, select, padding, gridPosition)
                tab == Tab.Albums -> AlbumsGrid(albums, specials, collections, albumsGrid, onOpen = {
                    CrashLog.note("open album ${Special.entries.find { s -> s.id == it }?.name ?: if (collectionIdOf(it) != null) "collection" else "folder"}")
                    albumId = it
                }, padding, albumActions::onAlbum, albumActions::newAlbum)
                allPlaces -> PlacesScreen(placesGrid, padding, openFromExplore)
                else -> ExploreScreen(albums, specials, exploreGrid, padding, openFromExplore, onAllPlaces = { allPlaces = true }) {
                    PeopleSection(onOpenPerson = openFromExplore, onSeeAll = { peopleOpen = true })
                }
            }
        }
        AlbumActionsHost(albumActions, media, albums, snackbar)
        if (inTrash) TrashScreen(trash, onBack = closeAlbum)
        // Steps aside while a person page is open, so Back from that page lands here again
        if (peopleOpen && tab == Tab.Explore && !inAlbum) PeopleScreen(peopleGrid, onBack = { peopleOpen = false }, onOpenPerson = openFromExplore)
        if (calendarOpen) {
            CalendarScreen(photos, onBack = { calendarOpen = false }) { day ->
                // At 7 columns the grid has month headers only, so the day's month goes to the top
                headerPosition(photos, day, byMonth = photosColumns.intValue >= MONTH_COLUMNS) { it.day }?.let { photosGrid.requestScrollToItem(it) }
                calendarOpen = false
            }
        }
        addingToCollection?.let { ids ->
            AddToCollectionSheet(ids.size, collections, onDismiss = { addingToCollection = null }, onPick = { id, name ->
                addToCollection(id, name, ids)
                addingToCollection = null
                selected = emptySet()
            })
        }
        viewerId?.let {
            Viewer(
                shown,
                it,
                onClose = { viewerId = null },
                onTrash = if (openCollection == null) { m -> trashWithUndo(listOf(m)) } else null,
                openFrom = openedFrom,
                closeTo = cellOnScreen,
            )
            // The Scaffold's snackbar sits under the viewer, so the viewer gets its own host for Undo
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
        }
        if (settingsOpen) SettingsScreen(look, onLook, onBack = { settingsOpen = false })
    }
}

@Composable
fun TitleBar(
    title: String,
    backIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Outlined.ArrowBack,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let { IconButton(onClick = it) { Icon(backIcon, "Back") } }
        Text(
            title,
            Modifier.weight(1f),
            fontSize = if (onBack == null) LocalLook.current.titleSize else 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
private fun PartialAccessBanner(onAllowAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Album can only see the photos you picked.",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TextButton(onClick = onAllowAll) { Text("Allow all") }
    }
}

@Composable
private fun PermissionScreen(modifier: Modifier, onAllow: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(LocalLook.current.icons.photos, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("See your photos here", style = MaterialTheme.typography.titleLarge)
        Text(
            "Album needs access to your photos and videos. Everything stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAllow) { Text("Allow access") }
        // If you tapped "Don't allow" twice, Android stops asking; this opens the app's settings page instead.
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text("Open settings") }
    }
}

@Composable
fun Message(text: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
