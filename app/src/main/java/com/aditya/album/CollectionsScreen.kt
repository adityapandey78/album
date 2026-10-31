package com.aditya.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The Collections tab (albums and their groups; people and pets, places, things and videos; then Recently deleted when
// Settings shows it here), the All albums page, an album's sheets, and the pieces pages and sheets share: AlbumCard,
// CoverRow, NameSheet, SheetButtons and the pushed-page scaffold.

/** The Albums section shows two rows of three; See all shows the rest. */
const val ALBUMS_IN_SECTION = 6

/**
 * A card in the Albums section: a folder on the phone ([folder]), a collection ([collection], a virtual album whose
 * photos stay where they are), a group of those ([group]) or Favourites ([special]). [id] is its album id, unique across
 * all of them, so it doubles as the lazy key. [key] is what pins, groups and covers are saved under, and [recent] (epoch
 * millis) orders the Default and Recently updated sorts. [cover] is the photo on its card (see [pickCover]).
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
    val group: GroupAlbum? = null,
    val special: Special? = null,
    val cover: Media? = folder?.cover ?: collection?.items?.firstOrNull(),
)

// A folder's recency is its newest photo, a collection's its last edit: each is the last time that album changed for you
fun folderEntry(a: Album, cover: Media = a.cover) = AlbumEntry(a.id, a.key, a.name, a.count, a.cover.taken, camera = a.name == "Camera", folder = a, cover = cover)

fun collectionEntry(c: CollectionAlbum, cover: Media? = c.items.firstOrNull()) =
    AlbumEntry(c.albumId, collectionKey(c.collection.id), c.name, c.items.size, c.collection.updated, collection = c, cover = cover)

/**
 * Folders and collections as one list: pinned first, then [sort]. Recent (Default) is albumsOf's order applied to both
 * kinds: Camera first, then the most recently changed.
 */
fun albumEntries(entries: List<AlbumEntry>, sort: AlbumSort, pinned: Set<String>): List<AlbumEntry> {
    val default = entries.sortedWith(compareByDescending<AlbumEntry> { it.camera }.thenByDescending { it.recent })
    return orderAlbums(default, sort, { it.key in pinned }, { it.name }, { it.count })
}

/** The Albums section's cards: the first [ALBUMS_IN_SECTION] of [entries], which [albumEntries] put pinned first. */
fun <T> albumsInSection(entries: List<T>): List<T> = entries.take(ALBUMS_IN_SECTION)

/** The Albums heading's link: "See all 14" when some are left out of the section, else "See all". */
fun seeAllAlbums(total: Int) = if (total > ALBUMS_IN_SECTION) "See all ${"%,d".format(total)}" else "See all"

/** Collections' and All albums' subtitle: how many cards there are, groups and Favourites included, "12 albums and groups". */
fun albumsSubtitle(cards: Int) = if (cards == 1) "1 album" else "${"%,d".format(cards)} albums and groups"

/** Whether a card shows under Pinned: Favourites always leads, the rest when the user pinned them. */
private fun AlbumEntry.pinnedIn(pinned: Set<String>) = special != null || key in pinned

/**
 * The Collections tab: the top albums (folders, collections and groups of them together, Favourites first), then
 * People and pets, Places, Things and Videos ([browseSections]), then Recently deleted when Settings shows it here.
 * Every card opens through [onOpen] with an album id, a group's through [onOpenGroup] with its group id; [onAllAlbums]
 * opens every album, [onPeople], [onPlaces] and [onThings] the whole of each. [contentPadding] already clears the status bar and the floating bottom bar. [media] is every item Album shows,
 * for covers. Long-pressing a card opens its sheet; folder actions go through [actions].
 */
@Composable
fun CollectionsScreen(
    albums: List<Album>,
    specialCounts: Map<Special, Int>,
    collections: List<CollectionAlbum>,
    media: List<Media>,
    actions: AlbumActions,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onOpen: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    onAllAlbums: () -> Unit,
    onPeople: () -> Unit,
    onPlaces: () -> Unit,
    onThings: () -> Unit,
    onSettings: () -> Unit,
) {
    val spec = AlbumTheme.spec
    val pinned by AlbumPrefs.pinned.collectAsState()
    val hidden by AlbumPrefs.hidden.collectAsState()
    val sort by AlbumPrefs.sort.collectAsState()
    val view = rememberAlbumsView(albums, collections, media)
    val browse = rememberBrowse(media)
    val entries = view.top
    val shown = remember(entries) { albumsInSection(entries) }
    var menu by remember { mutableStateOf<AlbumEntry?>(null) } // the card whose long-press sheet is open
    var more by rememberSaveable { mutableStateOf(false) }
    var grouping by rememberSaveable { mutableStateOf(false) }
    val allAlbums = {
        CrashLog.note("all albums")
        onAllAlbums()
    }

    LazyVerticalGrid(
        GridCells.Fixed(3),
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding.withSides(Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        fullRow("top") {
            // The cards, as See all counts them: a group is one, however many albums it holds
            LargeTopBar("Collections", subtitle = albumsSubtitle(entries.size)) {
                AlbumIconButton(Sym.Add, "New album", actions::newAlbum)
                AlbumIconButton(Sym.MoreVert, "More", { more = true })
            }
        }
        // Draws nothing once every job is done
        fullRow("indexing") { IndexingCard(Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3)) }

        if (entries.isNotEmpty()) fullRow("albums") { SectionHead("Albums", action = seeAllAlbums(entries.size), onAction = allAlbums) }
        albumCards(shown, pinned, hidden, onOpen, onOpenGroup) { menu = it }

        // What Album sorted (people and pets, places, things, videos) right here, not behind a Browse card: the user's call
        browseSections(browse, onOpen, onPeople, onPlaces, onThings)

        // The user's rule: Recently deleted sits quietly at the very end, and only while Settings shows it here. Private
        // safe and Hidden items live in Settings, under Privacy and safe.
        if (spec.appearance.trashInCollections) fullRow("trash") {
            val trashed = specialCounts[Special.Trash] ?: 0
            Group(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s6)) {
                GroupRow(Special.Trash.title, {
                    CrashLog.note("open trash")
                    onOpen(Special.Trash.id)
                }, icon = Sym.Delete, value = "%,d".format(trashed))
            }
        }
    }

    menu?.let { AlbumEntrySheet(it, view, collections, media, pinned, hidden, actions) { menu = null } }
    if (more) {
        Sheet({ more = false }, "Collections") {
            MenuRow(Sym.Add, "New album", {
                more = false
                actions.newAlbum()
            }, sub = "Name a folder, then move or copy photos in")
            MenuRow(Sym.CreateNewFolder, "New album group", {
                more = false
                grouping = true
            }, sub = "Keep albums together, like Day 1 to Day 4 of a trip")
            MenuRow(Sym.Sort, "Sort albums", {
                more = false
                allAlbums()
            }, sub = "Every album, by ${sort.label.lowercase()}")
            MenuRow(Sym.Settings, "Settings", {
                more = false
                onSettings()
            }, sub = SETTINGS_SUB)
        }
    }
    if (grouping) NewGroupSheets(view) { grouping = false }
}

/**
 * Every album, folders and collections together with groups of them: the sort chips, then Pinned (Favourites and what
 * the user pinned) and the rest in the chosen order. The page the Albums section's See all opens. Draws its own
 * background, insets and Back. Long-pressing a card opens its sheet.
 */
@Composable
fun AllAlbumsScreen(
    albums: List<Album>,
    collections: List<CollectionAlbum>,
    media: List<Media>,
    actions: AlbumActions,
    state: LazyGridState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
) {
    val pinned by AlbumPrefs.pinned.collectAsState()
    val hidden by AlbumPrefs.hidden.collectAsState()
    val sort by AlbumPrefs.sort.collectAsState()
    val view = rememberAlbumsView(albums, collections, media)
    val (first, rest) = remember(view, pinned) { view.top.partition { it.pinnedIn(pinned) } }
    var menu by remember { mutableStateOf<AlbumEntry?>(null) }
    var grouping by rememberSaveable { mutableStateOf(false) }

    PushedGridPage("Albums", albumsSubtitle(view.top.size), state, onBack, collapsed = true, actions = {
        AlbumIconButton(Sym.CreateNewFolder, "New album group", { grouping = true })
        AlbumIconButton(Sym.Add, "New album", actions::newAlbum)
    }) {
        fullRow("sort") { SortChips(sort) }
        if (first.isNotEmpty()) {
            fullRow("pinned") { SectionHead("Pinned") }
            albumCards(first, pinned, hidden, onOpen, onOpenGroup) { menu = it }
        }
        if (rest.isNotEmpty()) {
            fullRow("rest") { SectionHead(if (first.isEmpty()) "Albums" else "All albums") }
            albumCards(rest, pinned, hidden, onOpen, onOpenGroup) { menu = it }
        }
        fullRow("note") { Note("Press and hold an album to pin it, rename it or move it into a group.") }
    }

    menu?.let { AlbumEntrySheet(it, view, collections, media, pinned, hidden, actions) { menu = null } }
    if (grouping) NewGroupSheets(view) { grouping = false }
}

/** All albums' order, one chip each; pinned albums come first whatever the order. */
@Composable
private fun SortChips(sort: AlbumSort) {
    FlowRow(
        Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        AlbumSort.entries.forEach { s ->
            val on = s == sort
            // A button that says it's selected: tapping the picked one again changes nothing, so it isn't a toggle
            Chip(s.label, {
                CrashLog.note("sort albums ${s.name}")
                AlbumPrefs.setSort(s)
            }, Modifier.semantics { selected = on }, selected = on, toggle = false)
        }
    }
}

/**
 * A card for each of [entries]: its cover and name over its count (a system folder's path instead), or a group's covers
 * as a folder over its album count. [sub] replaces the line under the name. Long-press hands the entry to [onMenu]. A tap
 * opens it through [onOpen], or [onOpenGroup] for a group: every album and group card a screen shows opens from here.
 */
internal fun LazyGridScope.albumCards(
    entries: List<AlbumEntry>,
    pinned: Set<String>,
    hidden: Set<String>,
    onOpen: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    sub: ((AlbumEntry) -> String)? = null,
    onMenu: (AlbumEntry) -> Unit,
) = items(entries, key = { it.id }) { e ->
    val folder = e.folder
    val group = e.group
    val isHidden = folder != null && folder.key in hidden
    // Favourites leads whatever is pinned, so it needs no pin of its own
    val isPinned = e.special == null && e.key in pinned
    AlbumCard(
        name = e.name,
        // A group shows how many albums it holds: the word "albums" is what tells it from an album at a glance
        meta = listOf(
            sub?.invoke(e) ?: if (group != null) "${counted(group.albums.size, "album", "albums")} · ${"%,d".format(e.count)}"
            else "%,d".format(e.count) + (if (isHidden) " · Hidden" else ""),
        ),
        description = buildString {
            if (group != null) append("${e.name}, album group of ${counted(group.albums.size, "album", "albums")}") else append("${e.name}, ${itemCount(e.count)}")
            if (e.collection != null) append(", collection")
            if (isPinned) append(", pinned")
            if (isHidden) append(", hidden from Library")
        },
        onClick = {
            CrashLog.note(if (folder != null) "open folder" else if (group != null) "open group" else if (e.special != null) "open favourites" else "open collection")
            if (group != null) onOpenGroup(group.id) else onOpen(e.id)
        },
        modifier = Modifier.padding(bottom = Space.s4),
        onLongClick = { onMenu(e) },
        shape = RoundedCornerShape(if (group != null) Space.radiusLg else Space.radiusMd),
        badges = {
            if (group != null) FolderMark() else kindOf(e)?.let { KindMark(it) }
            if (isPinned) PinMark()
        },
    ) { if (group != null) FolderCover(group.albums) else EntryCover(e) }
}

/** What kind of album a card is, as a mark on its cover: Favourites, the camera's folder, screenshots or WhatsApp's. Null for the rest. */
private fun kindOf(e: AlbumEntry): Sym? = when {
    e.cover == null -> null // its empty cover already shows the icon
    e.special != null -> Sym.Favorite
    e.camera -> Sym.PhotoCamera
    e.folder?.name.equals("Screenshots", ignoreCase = true) -> Sym.ScreenshotMonitor
    e.folder?.name?.let(MediaApp::of).let { it == MediaApp.WhatsApp || it == MediaApp.WhatsAppBusiness } -> Sym.Chat
    else -> null
}

/** The mark at a cover's bottom left saying what kind of album it is, on scrim-photo. */
@Composable
private fun BoxScope.KindMark(sym: Sym) {
    val c = AlbumTheme.colors
    Box(Modifier.align(Alignment.BottomStart).padding(Space.s2).size(28.dp).background(c.scrimPhoto, CircleShape), contentAlignment = Alignment.Center) {
        Icon(sym, null, filled = true, size = Space.iconSm, tint = c.onPhoto)
    }
}

/** The pin at a pinned card's top right, on scrim-photo. TalkBack hears "pinned" from the card. */
@Composable
private fun BoxScope.PinMark() {
    val c = AlbumTheme.colors
    Box(Modifier.align(Alignment.TopEnd).padding(Space.s2).size(24.dp).background(c.scrimPhoto, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Sym.PushPin, null, filled = true, size = 14.dp, tint = c.onPhoto)
    }
}

/** Returns what Hide album does: after the privacy PIN (or at once while unlocked), the folder goes to Hidden items. */
@Composable
private fun rememberHideAlbum(): (Album) -> Unit {
    val context = LocalContext.current
    val privacy = rememberPrivacyAsk()
    return { album -> privacy.run("Hide ${album.name}") { Hidden.hide(context, emptyList(), listOf(album)) } }
}

private enum class EntryStep { Menu, Cover, Group }

/**
 * The long-press sheet of an album card, and an album page's Album options: a folder's own, a collection's, a group's or
 * Favourites'. Change cover and the group row open their own sheets in place of this one. [view] is the albums as
 * grouped on screen; [media] every item Album shows.
 */
@Composable
internal fun AlbumEntrySheet(
    entry: AlbumEntry,
    view: AlbumsView,
    collections: List<CollectionAlbum>,
    media: List<Media>,
    pinned: Set<String>,
    hidden: Set<String>,
    actions: AlbumActions,
    onDismiss: () -> Unit,
) {
    val onHide = rememberHideAlbum()
    var step by rememberSaveable { mutableStateOf(EntryStep.Menu) }
    val group = view.groupOf(entry.key)?.name
    val takeOut = {
        onDismiss()
        takeOutOfGroup(entry)
    }
    val share = {
        onDismiss()
        actions.shareZip(entry.name, entry.itemsIn(media))
    }
    when (step) {
        EntryStep.Menu -> {
            entry.folder?.let {
                AlbumSheet(
                    it, it.key in pinned, it.key in hidden, group, onDismiss,
                    onHide = {
                        onDismiss()
                        onHide(it)
                    },
                    onCover = { step = EntryStep.Cover },
                    onGroup = { step = EntryStep.Group },
                    onTakeOut = takeOut,
                    onShare = share,
                ) { action -> onDismiss(); actions.onAlbum(it, action) }
            }
            entry.collection?.let {
                CollectionSheet(it, collections, entry.key in pinned, group, { step = EntryStep.Cover }, { step = EntryStep.Group }, takeOut, share, onDismiss)
            }
            if (entry.group != null) GroupSheet(entry, view, media, actions, entry.key in pinned, onDismiss)
            // No pin: Favourites always leads the albums
            if (entry.special != null) Sheet(onDismiss, entry.name) {
                MenuRow(Sym.Image, "Change cover", { step = EntryStep.Cover }, sub = CHANGE_COVER_SUB)
                if (entry.count > 0) ShareZipRow(share)
            }
        }
        EntryStep.Cover -> AlbumCoverSheet(entry, media, onDismiss)
        EntryStep.Group -> GroupPicker(entry, view, onDismiss)
    }
}

/**
 * Long-press sheet of a folder album: pin, rename, change cover, its group ([group] names the one it's in), share as ZIP,
 * hide from the Library (a tidier timeline; the album stays here), Hide album (it and its photos go to Hidden items,
 * behind the privacy PIN), delete.
 */
@Composable
private fun AlbumSheet(
    album: Album,
    pinned: Boolean,
    hidden: Boolean,
    group: String?,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onCover: () -> Unit,
    onGroup: () -> Unit,
    onTakeOut: () -> Unit,
    onShare: () -> Unit,
    onAction: (AlbumAction) -> Unit,
) {
    Sheet(onDismiss, album.name) {
        MenuRow(Sym.PushPin, if (pinned) "Unpin" else "Pin to the top", { onAction(AlbumAction.Pin) }, sub = pinSub(pinned), trailing = null)
        MenuRow(Sym.Edit, "Rename", { onAction(AlbumAction.Rename) }, sub = "Renames the folder on your phone too")
        MenuRow(Sym.Image, "Change cover", onCover, sub = CHANGE_COVER_SUB)
        GroupMoveRows(group, onGroup, onTakeOut)
        ShareZipRow(onShare)
        MenuRow(
            if (hidden) Sym.Visibility else Sym.VisibilityOff,
            if (hidden) "Show in Library" else "Hide from Library",
            { onAction(AlbumAction.Hide) },
            sub = if (hidden) "Its photos show in the Library again" else "It stays here. Show it again from Hidden items.",
            trailing = null,
        )
        MenuRow(Sym.VisibilityOff, "Hide album", onHide, sub = "Moves it to Hidden items, behind your PIN. Other apps still show it.", trailing = null)
        MenuRow(Sym.Delete, "Delete album", { onAction(AlbumAction.Delete) }, sub = "Moves its ${itemCount(album.count)} to Trash", trailing = null)
    }
}

/** Share as ZIP in an album's sheet: every photo and video in it, as one file. */
@Composable
internal fun ShareZipRow(onShare: () -> Unit) = MenuRow(Sym.Share, "Share as ZIP", onShare, sub = "Every photo and video in it, as one file", trailing = null)

/** What Change cover does, on every album sheet. */
internal const val CHANGE_COVER_SUB = "Pick the photo on its card"

/** What Pin to the top, or Unpin when [pinned], does to an album, collection or group. */
internal fun pinSub(pinned: Boolean) = if (pinned) "Back to its place in the sort" else "Keeps it first in Albums"

/** Whether an album page has Album options: a folder (not one in Hidden items), a collection or Favourites. */
fun hasAlbumOptions(id: Long, album: Album?, collection: CollectionAlbum?) =
    (album != null && album.key !in AlbumPrefs.locked.value) || collection != null || id == Special.Favourites.id

/** An album page's Album options: the sheet its card's long-press opens in Collections. */
@Composable
fun AlbumOptionsSheet(id: Long, albums: List<Album>, collections: List<CollectionAlbum>, media: List<Media>, actions: AlbumActions, onDismiss: () -> Unit) {
    val pinned by AlbumPrefs.pinned.collectAsState()
    val hidden by AlbumPrefs.hidden.collectAsState()
    val view = rememberAlbumsView(albums, collections, media)
    val entry = view.album(id)
    if (entry == null) {
        LaunchedEffect(Unit) { onDismiss() } // gone meanwhile
        return
    }
    AlbumEntrySheet(entry, view, collections, media, pinned, hidden, actions, onDismiss)
}

/** People's "Same person?" tile: icon, label and [meta] (the count) on surface-high, read by TalkBack as [description]. */
@Composable
fun UtilityTile(sym: Sym, label: String, meta: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusMd)
    Row(
        modifier.fillMaxWidth().heightIn(min = Space.target + Space.s2).clip(shape).background(c.surfaceHigh, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sym, null, tint = c.ink)
        Text(label, Modifier.weight(1f), style = AlbumTheme.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(meta, style = AlbumTheme.type.meta, color = c.inkMuted)
    }
}

// ---------- Shared pieces ----------

/** Shrinks to [to] while [source] is pressed, springing back; with Remove animations on it changes at once. */
@Composable
internal fun Modifier.pressShrink(source: InteractionSource, to: Float): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) to else 1f,
        if (AlbumTheme.spec.still) snap() else spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * The design system's AlbumCard: a square cover (round for a person, [shape] otherwise) on surface-high that shrinks a
 * little while pressed, the name in label and [meta] lines under it. [badges] sit over the cover's corners, unclipped.
 * TalkBack reads [description] instead of the bare lines. Long-press calls [onLongClick] when set.
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
    shape: Shape = if (round) CircleShape else RoundedCornerShape(Space.radiusMd),
    badges: @Composable BoxScope.() -> Unit = {},
    cover: @Composable BoxScope.() -> Unit,
) {
    val c = AlbumTheme.colors
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val align = if (round) Alignment.CenterHorizontally else Alignment.Start
    Column(
        // Not clipped to rounded corners: that cut into the last text line, which sits in the bottom corner
        modifier
            .opensScreen() // the album, person or place grows out of this card
            .combinedClickable(
                source,
                indication = null, // the cover shrinking is the feedback, as in the design
                role = Role.Button,
                onLongClickLabel = onLongClick?.let { "More options" },
                onLongClick = onLongClick?.let { { haptics.longPress(); it() } },
                hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(10.dp), // the design's 8 under the cover and 2 between lines
        horizontalAlignment = align,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).pressShrink(source, 0.96f)) {
            Box(Modifier.matchParentSize().clip(shape).background(c.surfaceHigh), contentAlignment = Alignment.Center, content = cover)
            badges()
        }
        Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = align) {
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
 * Asks for a name in a sheet: [hint] above, the field (showing [placeholder] while empty), one line under it ([note], in
 * danger when [isError]), anything in [extra] (suggestions), then Cancel and [confirm]. The caller owns the text and
 * decides what's valid ([enabled]).
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
    hint: String? = null,
    placeholder: String = "Name",
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    Sheet(onDismiss, title) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            hint?.let { Text(it, style = AlbumTheme.type.body, color = AlbumTheme.colors.inkMuted) }
            OutlinedTextField(
                text,
                onText,
                Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = AlbumTheme.type.body,
                placeholder = { Text(placeholder) },
                supportingText = note?.let { { Text(it) } },
                isError = isError,
                singleLine = true,
                shape = RoundedCornerShape(Space.radiusMd),
                keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled) onConfirm() }),
                colors = albumFieldColors(),
            )
            extra()
            SheetButtons(confirm, enabled, onDismiss, onConfirm)
        }
        LaunchedEffect(focus) { focus.requestFocus() }
    }
}

/** A sheet's closing pair: Cancel, and the wider primary [confirm], off until [enabled]. */
@Composable
fun SheetButtons(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.padding(top = Space.s2), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        AlbumButton("Cancel", onCancel, Modifier.weight(1f).heightIn(min = 52.dp))
        AlbumButton(confirm, onConfirm, Modifier.weight(1.4f).heightIn(min = 52.dp), ButtonVariant.Primary, enabled = enabled)
    }
}

/** The design's text field: surface-high with a line-strong edge, a focus ring in flash-ink, ink text, danger for problems. */
@Composable
fun albumFieldColors(): TextFieldColors {
    val c = AlbumTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.ink, unfocusedTextColor = c.ink, errorTextColor = c.ink,
        focusedContainerColor = c.surfaceHigh, unfocusedContainerColor = c.surfaceHigh, errorContainerColor = c.surfaceHigh,
        cursorColor = c.ink, errorCursorColor = c.danger,
        selectionColors = TextSelectionColors(handleColor = c.flashInk, backgroundColor = c.flashSoft),
        focusedBorderColor = c.flashInk, unfocusedBorderColor = c.lineStrong, errorBorderColor = c.danger,
        focusedLabelColor = c.inkMuted, unfocusedLabelColor = c.inkMuted, errorLabelColor = c.danger,
        focusedSupportingTextColor = c.inkMuted, unfocusedSupportingTextColor = c.inkMuted, errorSupportingTextColor = c.danger,
        focusedPlaceholderColor = c.inkMuted, unfocusedPlaceholderColor = c.inkMuted,
    )
}

/**
 * A full-screen page pushed over the tabs: a 3-column grid that starts with a large title, with the one-row bar taking
 * over once that title scrolls away. [collapsed] keeps the one-row bar from the start, as the pages Collections opens
 * have it (All albums, a group, Things). Both bars show [actions]. Draws its own background, insets and Back.
 */
@Composable
fun PushedGridPage(
    title: String,
    subtitle: String?,
    state: LazyGridState,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    collapsed: Boolean = false,
    content: LazyGridScope.() -> Unit,
) {
    val c = AlbumTheme.colors
    val collapseAt = with(LocalDensity.current) { (Space.target + Space.s6).roundToPx() } // about where the large title starts
    val scrolled by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > collapseAt } }
    val bars = WindowInsets.systemBars.asPaddingValues()
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(c.canvas).pointerInput(Unit) {}) { // catches taps that would reach the screen underneath
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(
                start = Space.s4, end = Space.s4, bottom = bars.calculateBottomPadding() + Space.s8,
                // the one-row bar is 56dp and its hairline, then a little air
                top = bars.calculateTopPadding() + if (collapsed) CollapsedBarHeight + Space.s2 else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            if (!collapsed) fullRow("top") { LargeTopBar(title, subtitle = subtitle, onBack = onBack, actions = actions) }
            content()
        }
        CollapsingBar(collapsed || scrolled) {
            Box(Modifier.background(c.canvas).statusBarsPadding()) { CollapsedTopBar(title, subtitle = subtitle, onBack = onBack, actions = actions) }
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

/** A thin progress line: flash-ink on surface-high. It keeps moving with Remove animations on, since it's information. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    Box(modifier.fillMaxWidth().height(Space.s1).progressSemantics(fraction).clip(CircleShape).background(c.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.flashInk, CircleShape))
    }
}
