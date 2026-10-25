package com.aditya.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The Collections tab (albums, people, places, things in one place) and the pieces its pages and sheets share:
// AlbumCard, CoverRow, NameSheet and the pushed-page scaffold used by Places and People.

private const val PEOPLE_IN_SECTION = 6 // two rows of faces; See all shows everyone
private const val PLACES_IN_ROW = 12

/** The design's scroll-row card: covers in Places' horizontal row are this wide. */
private val ROW_CARD = 96.dp

/**
 * A card in the Albums section: a folder on the phone ([folder]) or a collection ([collection], a virtual album whose
 * photos stay where they are). [id] is its album id, unique across both, so it doubles as the lazy key. [key] is what
 * pins are saved under, and [recent] (epoch millis) orders the Default and Recently updated sorts.
 */
class AlbumEntry(
    val id: Long,
    val key: String,
    val name: String,
    val count: Int,
    val recent: Long,
    val camera: Boolean = false,
    val folder: Album? = null,
    val collection: CollectionAlbum? = null,
)

// A folder's recency is its newest photo, a collection's its last edit: each is the last time that album changed for you
fun folderEntry(a: Album) = AlbumEntry(a.id, a.key, a.name, a.count, a.cover.taken, camera = a.name == "Camera", folder = a)

fun collectionEntry(c: CollectionAlbum) =
    AlbumEntry(c.albumId, collectionKey(c.collection.id), c.name, c.items.size, c.collection.updated, collection = c)

/**
 * Folders and collections as one list: pinned first, then [sort]. Default is albumsOf's order applied to both kinds:
 * Camera first, then the most recently changed.
 */
fun albumEntries(entries: List<AlbumEntry>, sort: AlbumSort, pinned: Set<String>): List<AlbumEntry> {
    val default = entries.sortedWith(compareByDescending<AlbumEntry> { it.camera }.thenByDescending { it.recent })
    return orderAlbums(default, sort, { it.key in pinned }, { it.name }, { it.count }, { it.recent })
}

/** Collections' subtitle: "12 albums · 3 people · 1 place". */
fun collectionsSubtitle(albums: Int, people: Int, places: Int) =
    "${counted(albums, "album", "albums")} · ${counted(people, "person", "people")} · ${counted(places, "place", "places")}"

/**
 * The Collections tab: utilities, albums (folders and collections together), people, places and things, with Recently
 * deleted last. Every card opens through [onOpen] with an album id. [contentPadding] already clears the status bar and
 * the floating bottom bar. Long-pressing a folder's card offers [onAction]; a collection's card handles its own.
 */
@Composable
fun CollectionsScreen(
    albums: List<Album>,
    specialCounts: Map<Special, Int>,
    collections: List<CollectionAlbum>,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onOpen: (Long) -> Unit,
    onAllPlaces: () -> Unit,
    onAllPeople: () -> Unit,
    onAction: (Album, AlbumAction) -> Unit,
    onNewAlbum: () -> Unit,
    onSettings: () -> Unit,
) {
    val spec = AlbumTheme.spec
    val index by SmartIndex.progress.collectAsState()
    val places = Explore.places.collectAsState().value
    val things = Explore.things.collectAsState().value
    val everyone by People.albums.collectAsState()
    val scanning by People.progress.collectAsState()
    val pinned by AlbumPrefs.pinned.collectAsState()
    val hidden by AlbumPrefs.hidden.collectAsState()
    val sort by AlbumPrefs.sort.collectAsState()
    val entries = remember(albums, collections, sort, pinned) {
        albumEntries(albums.map(::folderEntry) + collections.map(::collectionEntry), sort, pinned)
    }
    val people = remember(everyone) { shownPeople(everyone) }
    val anyHidden = remember(everyone) { everyone.any { it.hidden } }
    val names = remember(places) { placeNames(places.orEmpty().map { it.key }) }
    // Utilities open the same albums as the cards below, so like them they include folders hidden from the Library
    val utilities = remember(albums, specialCounts) {
        val screenshots = albums.filter { it.name.equals("Screenshots", ignoreCase = true) }.maxByOrNull { it.count }
        listOfNotNull(
            Utility(Sym.Favorite, Special.Favourites.title, specialCounts[Special.Favourites] ?: 0, Special.Favourites.id),
            Utility(Sym.Videocam, Special.Videos.title, specialCounts[Special.Videos] ?: 0, Special.Videos.id),
            screenshots?.let { Utility(Sym.ScreenshotMonitor, "Screenshots", it.count, it.id) }, // only when the phone has that folder
        )
    }
    var menu by remember { mutableStateOf<AlbumEntry?>(null) } // the card whose long-press sheet is open
    var sorting by rememberSaveable { mutableStateOf(false) }
    val open = { what: String, id: Long ->
        CrashLog.note("open $what")
        onOpen(id)
    }

    LazyVerticalGrid(
        GridCells.Fixed(3),
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding.withSides(Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        fullRow("top") {
            LargeTopBar("Collections", subtitle = collectionsSubtitle(entries.size, people.size, places.orEmpty().size)) {
                AlbumIconButton(Sym.Add, "New album", onNewAlbum)
                AlbumIconButton(Sym.Settings, "Settings", onSettings)
            }
        }
        if (index.done < index.total) fullRow("indexing") {
            ProgressNote("Organising your photos: ${"%,d".format(index.done)} of ${"%,d".format(index.total)}", index.done / index.total.toFloat())
        }
        fullRow("utilities") {
            Column(Modifier.padding(horizontal = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                utilities.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        pair.forEach { u -> UtilityTile(u.sym, u.title, u.count, Modifier.weight(1f)) { open("utility ${u.title}", u.albumId) } }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }

        if (entries.isNotEmpty()) fullRow("albums") { SectionHead("Albums", action = "Sort", onAction = { sorting = true }) }
        items(entries, key = { it.id }) { e ->
            val folder = e.folder
            val isHidden = folder != null && folder.key in hidden
            AlbumCard(
                name = e.name,
                meta = listOfNotNull("%,d".format(e.count) + (if (isHidden) " · Hidden" else ""), folder?.cover?.path?.trimEnd('/')),
                description = buildString {
                    append("${e.name}, ${itemCount(e.count)}")
                    append(if (folder != null) ", folder ${folder.cover.path.trimEnd('/')}" else ", collection")
                    if (e.key in pinned) append(", pinned")
                    if (isHidden) append(", hidden from Library")
                },
                onClick = { open(if (folder != null) "folder" else "collection", e.id) },
                modifier = Modifier.padding(bottom = Space.s4),
                onLongClick = { menu = e },
            ) {
                val cover = folder?.cover ?: e.collection?.items?.firstOrNull()
                if (cover != null) Thumbnail(cover, Modifier.matchParentSize()) else Icon(Sym.CollectionsBookmark, null, tint = AlbumTheme.colors.inkMuted)
                if (e.key in pinned) Badge("Pinned", Modifier.align(Alignment.TopStart).padding(Space.s2), BadgeTone.Photo, Sym.PushPin)
            }
        }

        // Shows once people are found, or while looking; See all stays while only hidden people are left, so hiding can be undone
        if (people.isNotEmpty() || anyHidden || scanning.total > 0) {
            fullRow("people") { SectionHead("People", action = "See all".takeIf { people.isNotEmpty() || anyHidden }, onAction = onAllPeople) }
            if (scanning.total > 0) fullRow("people scan") { Note(scanningText(scanning)) }
            items(people.take(PEOPLE_IN_SECTION), key = { "person ${it.cluster}" }) { p ->
                PersonCard(p, Modifier.padding(bottom = Space.s4)) { open("person", p.albumId) }
            }
        }

        fullRow("places") {
            SectionHead("Places", action = "See all".takeIf { !places.isNullOrEmpty() }, onAction = {
                CrashLog.note("all places")
                onAllPlaces()
            })
        }
        fullRow("place row") {
            when {
                places == null -> {} // loading: a moment on first open only
                places.isEmpty() -> Note("Places appear for photos with a location")
                else -> LazyRow(
                    contentPadding = PaddingValues(horizontal = Space.s4),
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    items(places.take(PLACES_IN_ROW), key = { placeKey(it.key) }) {
                        PlaceCard(it, names.getValue(it.key), Modifier.width(ROW_CARD)) { openPlace(it.key, onOpen) }
                    }
                }
            }
        }

        fullRow("things") { SectionHead("Things") }
        fullRow("thing chips") {
            when {
                things == null -> {}
                things.isEmpty() -> Note("Food, pets, sunsets and more appear here as Album organises your photos")
                else -> FlowRow(
                    Modifier.padding(horizontal = Space.s4),
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                    verticalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    things.forEach { t -> Chip(t.key.title, { open("thing ${t.key.name}", thingIdOf(t.key)) }, count = "%,d".format(t.items.size), toggle = false) }
                }
            }
        }

        // The user's rule: private places sit quietly at the very end, and Settings can leave them out of here altogether
        if (!spec.appearance.hidePrivate) fullRow("private") {
            UtilityTile(Sym.Delete, Special.Trash.title, specialCounts[Special.Trash] ?: 0, Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s8), quiet = true) {
                open("trash", Special.Trash.id)
            }
        }
    }

    menu?.let { e ->
        val close = { menu = null }
        e.folder?.let { AlbumSheet(it, it.key in pinned, it.key in hidden, close) { action -> close(); onAction(it, action) } }
        e.collection?.let { CollectionSheet(it, collections, e.key in pinned, close) }
    }
    if (sorting) AlbumSortSheet { sorting = false }
}

/** Long-press sheet of a folder album: pin, rename, hide from the Library, delete. */
@Composable
private fun AlbumSheet(album: Album, pinned: Boolean, hidden: Boolean, onDismiss: () -> Unit, onAction: (AlbumAction) -> Unit) {
    val c = AlbumTheme.colors
    Sheet(onDismiss, album.name) {
        Text(album.cover.path.trimEnd('/'), Modifier.padding(bottom = Space.s2), style = AlbumTheme.type.meta, color = c.inkMuted)
        MenuRow(Sym.PushPin, if (pinned) "Unpin" else "Pin", { onAction(AlbumAction.Pin) }, sub = "Pinned albums come first".takeIf { !pinned }, trailing = null)
        MenuRow(Sym.Edit, "Rename", { onAction(AlbumAction.Rename) }, trailing = null)
        MenuRow(
            if (hidden) Sym.Visibility else Sym.VisibilityOff,
            if (hidden) "Show in Library" else "Hide from Library",
            { onAction(AlbumAction.Hide) },
            sub = if (hidden) "Its photos show in the Library again" else "Its photos stay in this album",
            trailing = null,
        )
        MenuRow(Sym.Delete, "Delete", { onAction(AlbumAction.Delete) }, sub = "Moves its ${itemCount(album.count)} to Trash", trailing = null, danger = true)
    }
}

private class Utility(val sym: Sym, val title: String, val count: Int, val albumId: Long)

/** Favourites, Videos, Screenshots: icon, label and count on surface-high. [quiet] drops the fill, for the last row. */
@Composable
private fun UtilityTile(sym: Sym, label: String, count: Int, modifier: Modifier = Modifier, quiet: Boolean = false, onClick: () -> Unit) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusMd)
    Row(
        modifier.fillMaxWidth().heightIn(min = Space.target + Space.s2).clip(shape)
            .then(if (quiet) Modifier else Modifier.background(c.surfaceHigh, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label, ${itemCount(count)}" }
            .padding(horizontal = if (quiet) Space.s1 else Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sym, null, tint = if (quiet) c.inkMuted else c.ink)
        Text(label, Modifier.weight(1f), style = AlbumTheme.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%,d".format(count), style = AlbumTheme.type.meta, color = c.inkMuted)
        if (quiet) Icon(Sym.ChevronRight, null, tint = c.inkMuted)
    }
}

// ---------- Shared pieces ----------

/**
 * The design system's AlbumCard: a square cover (round for a person) on surface-high, the name in label and [meta] lines
 * under it. TalkBack reads [description] instead of the bare lines. Long-press calls [onLongClick] when set.
 */
@Composable
fun AlbumCard(
    name: String,
    meta: List<String>,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    nameColor: Color = AlbumTheme.colors.ink,
    onLongClick: (() -> Unit)? = null,
    cover: @Composable BoxScope.() -> Unit,
) {
    val c = AlbumTheme.colors
    val haptics = rememberHaptics()
    val align = if (round) Alignment.CenterHorizontally else Alignment.Start
    Column(
        // Not clipped to rounded corners: that cut into the last text line, which sits in the bottom corner
        modifier
            .combinedClickable(
                onLongClickLabel = onLongClick?.let { "More options" },
                onLongClick = onLongClick?.let { { haptics.longPress(); it() } },
                hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Space.s2),
        horizontalAlignment = align,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(if (round) CircleShape else RoundedCornerShape(Space.radiusMd)).background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
            content = cover,
        )
        Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = align) {
            Text(name, style = AlbumTheme.type.label, color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            meta.forEach { Text(it, style = AlbumTheme.type.meta, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A MenuRow with a picture instead of an icon, for the album, collection and person pickers. [round] for faces. */
@Composable
fun CoverRow(label: String, sub: String?, onClick: () -> Unit, round: Boolean = false, cover: @Composable BoxScope.() -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.target + Space.s2).clip(RoundedCornerShape(Space.radiusMd))
            .clickable(role = Role.Button, onClick = onClick).padding(Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Space.target).clip(if (round) CircleShape else RoundedCornerShape(Space.radiusMd)).background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
            content = cover,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = AlbumTheme.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = AlbumTheme.type.meta, color = c.inkMuted, maxLines = 1) }
        }
    }
}

/**
 * Asks for a name in a sheet: the field, one line under it ([note], in danger when [isError]), anything in [extra]
 * (suggestions), then Cancel and [confirm]. The caller owns the text and decides what's valid ([enabled]).
 */
@Composable
fun NameSheet(
    title: String,
    text: TextFieldValue,
    onText: (TextFieldValue) -> Unit,
    confirm: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    note: String? = null,
    isError: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = AlbumTheme.colors
    val focus = remember { FocusRequester() }
    Sheet(onDismiss, title) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            OutlinedTextField(
                text,
                onText,
                Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = AlbumTheme.type.body,
                label = { Text("Name") },
                supportingText = note?.let { { Text(it) } },
                isError = isError,
                singleLine = true,
                shape = RoundedCornerShape(Space.radiusMd),
                keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled) onConfirm() }),
                // The design's field: surface-high with a line-strong edge, ink text, danger for problems
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.ink, unfocusedTextColor = c.ink, errorTextColor = c.ink,
                    focusedContainerColor = c.surfaceHigh, unfocusedContainerColor = c.surfaceHigh, errorContainerColor = c.surfaceHigh,
                    cursorColor = c.ink, errorCursorColor = c.danger,
                    selectionColors = TextSelectionColors(handleColor = c.flashInk, backgroundColor = c.flashSoft),
                    focusedBorderColor = c.ink, unfocusedBorderColor = c.lineStrong, errorBorderColor = c.danger,
                    focusedLabelColor = c.inkMuted, unfocusedLabelColor = c.inkMuted, errorLabelColor = c.danger,
                    focusedSupportingTextColor = c.inkMuted, unfocusedSupportingTextColor = c.inkMuted, errorSupportingTextColor = c.danger,
                ),
            )
            extra()
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                AlbumButton("Cancel", onDismiss, Modifier.weight(1f))
                AlbumButton(confirm, onConfirm, Modifier.weight(1.5f), ButtonVariant.Primary, enabled = enabled)
            }
        }
        LaunchedEffect(focus) { focus.requestFocus() }
    }
}

/**
 * A full-screen page pushed over the tabs (Places, People): a 3-column grid that starts with a large title, with the
 * one-row bar taking over once that title scrolls away. Draws its own background, insets and Back.
 */
@Composable
fun PushedGridPage(title: String, subtitle: String?, state: LazyGridState, onBack: () -> Unit, content: LazyGridScope.() -> Unit) {
    val c = AlbumTheme.colors
    val collapseAt = with(LocalDensity.current) { (Space.target + Space.s6).roundToPx() } // about where the large title starts
    val collapsed by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > collapseAt } }
    val bars = WindowInsets.systemBars.asPaddingValues()
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(c.canvas).pointerInput(Unit) {}) { // catches taps that would reach the screen underneath
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(
                start = Space.s4, end = Space.s4, top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding() + Space.s8,
            ),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            fullRow("top") { LargeTopBar(title, subtitle = subtitle, onBack = onBack) }
            content()
        }
        CollapsingBar(collapsed) {
            Box(Modifier.background(c.canvas).statusBarsPadding()) { CollapsedTopBar(title, subtitle = subtitle, onBack = onBack) }
        }
    }
}

/**
 * A full-width grid row. Rows bring their own side margin (LargeTopBar, SectionHead, chips), so they stretch over the
 * grid's, which is there for the cards; a horizontal list in one then scrolls edge to edge.
 */
fun LazyGridScope.fullRow(key: Any, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(Space.s4)) { content() } }

private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun PaddingValues.withSides(side: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + side,
        top = calculateTopPadding(),
        end = calculateEndPadding(direction) + side,
        bottom = calculateBottomPadding(),
    )
}

/** A helper line under a heading, in caption. */
@Composable
fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, bottom = Space.s3), style = AlbumTheme.type.caption, color = AlbumTheme.colors.inkMuted)
}

/** A [Note] with a thin progress line under it, for work that takes a while (organising photos). */
@Composable
private fun ProgressNote(text: String, fraction: Float) {
    Column(Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(text, style = AlbumTheme.type.caption, color = AlbumTheme.colors.inkMuted)
        ProgressLine(fraction)
    }
}

/** A thin progress line: flash-ink on surface-high. It keeps moving with Remove animations on, since it's information. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    Box(modifier.fillMaxWidth().height(Space.s1).progressSemantics(fraction).clip(CircleShape).background(c.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.flashInk, CircleShape))
    }
}
