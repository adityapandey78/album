package com.aditya.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Album groups ("Trips" holding "Goa 2024" and "Manali") and album covers. Both live in collections.db with the
// collections, under the keys pins use, so no file moves on disk. One level deep: a group holds albums, never groups.
// Nesting would need a tree in every album list and a way back up from each level, for little gain over a second group.

/** Groups open through ids in their own band, above collections (2^48), so a group's card key never clashes with an album's. */
private const val GROUP_IDS = 1L shl 52

/** What a group's pin and cover are saved under. Folder keys end in "/" and collection keys start "collection:", so none meet. */
fun groupKey(id: Long) = "group:$id"

fun groupAlbumId(id: Long) = GROUP_IDS + id

/** Why [name] can't name a group, or null if it can. [taken] are the other groups' names, compared ignoring case. */
fun groupNameError(name: String, taken: Collection<String>): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Enter a name"
        taken.any { it.trim().equals(trimmed, ignoreCase = true) } -> "You already have a group with this name"
        else -> null
    }
}

/** A group with the albums in it that are on the phone now, in display order (pinned first, then the sort). */
class GroupAlbum(val group: AlbumGroup, val albums: List<AlbumEntry>) {
    val id get() = group.id
    val name get() = group.name
}

/** A group as a card among the albums: it counts all its albums' items and is as recent as its newest album. */
fun groupEntry(g: GroupAlbum, cover: Media?) =
    AlbumEntry(groupAlbumId(g.id), groupKey(g.id), g.name, g.albums.sumOf { it.count }, g.albums.maxOf { it.recent }, group = g, cover = cover)

/**
 * The Albums section and All albums: [top] holds the albums in no group and a card for each group, in [albumEntries]
 * order; [groups] are those groups' cards. A group shows while one of its albums does, so one whose albums are all out of
 * sight (an SD card taken out, a folder in Hidden items) hides with them and comes back with them.
 */
class AlbumsView(val top: List<AlbumEntry>, val groups: List<AlbumEntry>) {
    fun group(id: Long): GroupAlbum? = groups.firstNotNullOfOrNull { e -> e.group?.takeIf { it.id == id } }

    /** The group card of the group album [key] is in, or null. */
    fun groupOf(key: String): AlbumEntry? = groups.find { e -> e.group!!.albums.any { it.key == key } }
}

/**
 * Sorts [entries] (every folder and collection) with [albumEntries], then puts each one listed in [members] into its group.
 * [coverOf] picks a group's cover from its albums.
 */
fun albumsView(
    entries: List<AlbumEntry>,
    groups: List<AlbumGroup>,
    members: List<GroupMember>,
    sort: AlbumSort,
    pinned: Set<String>,
    coverOf: (GroupAlbum) -> Media? = { it.albums.first().cover },
): AlbumsView {
    val all = albumEntries(entries, sort, pinned)
    val byId = groups.associateBy { it.id }
    val groupOf = members.associate { it.albumKey to it.groupId }
    val inGroup = all.groupBy { groupOf[it.key]?.takeIf { id -> id in byId } }
    val cards = groups.mapNotNull { g -> inGroup[g.id]?.let { albums -> GroupAlbum(g, albums).let { groupEntry(it, coverOf(it)) } } }
    return AlbumsView(albumEntries(inGroup[null].orEmpty() + cards, sort, pinned), cards)
}

/**
 * An album's cover: the photo the user [chosen] while [find] still finds it and it still [belongs] in the album, else the
 * [automatic] one (a folder's or collection's newest photo, a group's first album's cover), like a person's cover face.
 */
fun <T : Any> pickCover(chosen: Long?, find: (Long) -> T?, belongs: (T) -> Boolean, automatic: T?): T? =
    chosen?.let(find)?.takeIf(belongs) ?: automatic

/** Whether [m] is in this album: a folder's file, a collection's item, or either in one of a group's albums. */
fun AlbumEntry.holds(m: Media): Boolean = when {
    folder != null -> m.bucketId == folder.id
    collection != null -> collection.items.any { it.id == m.id }
    else -> group?.albums?.any { it.holds(m) } == true
}

/** This album's photos and videos, newest first. A group's albums' together, each item once. [media] is every one Album shows. */
fun AlbumEntry.itemsIn(media: List<Media>): List<Media> = when {
    folder != null -> media.filter { it.bucketId == folder.id }
    collection != null -> collection.items
    else -> group?.albums?.flatMap { it.itemsIn(media) }?.distinctBy { it.id }?.sortedByDescending { it.taken }.orEmpty()
}

/** Every folder and collection, grouped and with their chosen covers, for Collections, All albums and a group's page. */
@Composable
fun rememberAlbumsView(albums: List<Album>, collections: List<CollectionAlbum>, media: List<Media>): AlbumsView {
    val organisation by Collections.organisation.collectAsState()
    val pinned by AlbumPrefs.pinned.collectAsState()
    val sort by AlbumPrefs.sort.collectAsState()
    val byId = remember(media) { media.associateBy { it.id } }
    return remember(albums, collections, organisation, pinned, sort, byId) {
        val o = organisation ?: return@remember AlbumsView(emptyList(), emptyList()) // a moment at launch
        val chosen = o.covers.associate { it.albumKey to it.mediaId }
        val entries = albums.map { a -> folderEntry(a, pickCover(chosen[a.key], byId::get, { it.bucketId == a.id }, a.cover) ?: a.cover) } +
            collections.map { c ->
                collectionEntry(c, pickCover(chosen[collectionKey(c.collection.id)], { id -> c.items.find { it.id == id } }, { true }, c.items.firstOrNull()))
            }
        albumsView(entries, o.groups, o.members, sort, pinned) { g ->
            pickCover(chosen[groupKey(g.id)], byId::get, { m -> g.albums.any { it.holds(m) } }, g.albums.first().cover)
        }
    }
}

// ---------- Saving ----------

private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs [block]; a database error (full storage, say) is logged and gives null instead of crashing Album. */
internal suspend fun <T> savedOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    CrashLog.note("organise write failed: $e")
    null
}

/**
 * Saves an organising change in the app's scope, so a sheet closing or the phone turning never drops it, and shows what
 * [change] returns in the snackbar (see [AlbumActionsHost]).
 */
internal fun organise(note: String, change: suspend () -> AlbumOps.Message?) {
    CrashLog.note(note)
    writes.launch {
        try {
            change()?.let { AlbumOps.messages.trySend(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.note("organise failed: $e")
            AlbumOps.say("Couldn't save that change")
        }
    }
}

/** Makes [move] for album [entry], then says [text] with Undo, which puts the album back where it was. */
private fun moveAlbum(entry: AlbumEntry, text: String, move: suspend () -> GroupMove) = organise("group move") {
    val done = move()
    AlbumOps.Message(text) {
        organise("undo group move") {
            Collections.moveBack(entry.key, done)
            null
        }
    }
}

// ---------- Sheets ----------

/**
 * Picks where album [entry] goes: a new group (named here), another group, or out of the one it's in ([current]). The
 * albums stay where they are on the phone.
 */
@Composable
fun GroupPicker(entry: AlbumEntry, view: AlbumsView, onDismiss: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    val current = view.groupOf(entry.key)
    if (naming) {
        GroupNameSheet("New group", "", "Create", view.groups.map { it.name }, onDismiss) { name ->
            onDismiss()
            moveAlbum(entry, "Moved ${entry.name} to $name") { Collections.newGroup(name, entry.key) }
        }
        return
    }
    Sheet(onDismiss, if (current == null) "Add ${entry.name} to" else "Move ${entry.name} to") {
        LazyColumn {
            item(key = "new") { MenuRow(Sym.Add, "New group", { naming = true }, sub = "Keep albums together, like Trips", trailing = null) }
            items(view.groups.filter { it !== current }, key = { it.id }) { g ->
                CoverRow(g.name, counted(g.group!!.albums.size, "album", "albums"), {
                    onDismiss()
                    moveAlbum(entry, "Moved ${entry.name} to ${g.name}") { Collections.moveToGroup(entry.key, g.group.id) }
                }) { EntryCover(g) }
            }
            current?.let { g ->
                item(key = "out") {
                    MenuRow(Sym.RemoveCircle, "Take out of ${g.name}", {
                        onDismiss()
                        moveAlbum(entry, "Took ${entry.name} out of ${g.name}") { Collections.moveToGroup(entry.key, null) }
                    }, sub = "It shows on its own in Albums again", trailing = null)
                }
            }
        }
    }
}

/**
 * Change cover for an album, collection or group: its photos, newest first, the current cover checked. Tapping one keeps it
 * as the cover until it leaves the album; Choose automatically (shown once one is picked) hands the choice back to Album.
 */
@Composable
fun AlbumCoverSheet(entry: AlbumEntry, media: List<Media>, onDismiss: () -> Unit) {
    val items = remember(entry, media) { entry.itemsIn(media) }
    val chosen = Collections.organisation.collectAsState().value?.covers?.any { it.albumKey == entry.key } == true
    fun pick(m: Media?) {
        onDismiss()
        organise(if (m == null) "cover automatic" else "cover chosen") {
            Collections.setCover(entry.key, m?.id)
            null
        }
    }
    Sheet(onDismiss, "Change cover") {
        if (chosen) {
            MenuRow(Sym.Refresh, "Choose automatically", { pick(null) }, sub = if (entry.group != null) "Album shows its first album's cover" else "Album shows the newest photo", trailing = null)
        }
        LazyVerticalGrid(
            GridCells.Fixed(4),
            Modifier.padding(top = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s1),
            verticalArrangement = Arrangement.spacedBy(Space.s1),
        ) {
            items(items, key = { it.id }) { m ->
                val current = m.id == entry.cover?.id
                Box(
                    Modifier.aspectRatio(1f)
                        .clickable(onClickLabel = "Use as cover", role = Role.Button) { pick(m) }
                        .semantics { contentDescription = if (current) "Cover now" else if (m.isVideo) "Video" else "Photo" },
                ) {
                    Thumbnail(m, Modifier.matchParentSize())
                    if (current) SelectCheck(true, Modifier.align(Alignment.TopEnd).padding(Space.s1))
                }
            }
        }
    }
}

private enum class GroupStep { Menu, Rename, Cover }

/**
 * A group's sheet, from a long-press on its card or More on its page: pin, rename, change cover, share as ZIP (a folder
 * per album) and Ungroup, which only takes the group away; its albums stay, on their own. [onGone] runs after Ungroup.
 */
@Composable
fun GroupSheet(entry: AlbumEntry, view: AlbumsView, media: List<Media>, actions: AlbumActions, pinned: Boolean, onDismiss: () -> Unit, onGone: () -> Unit = {}) {
    val g = entry.group ?: return
    var step by rememberSaveable { mutableStateOf(GroupStep.Menu) }
    when (step) {
        GroupStep.Menu -> Sheet(onDismiss, g.name) {
            MenuRow(Sym.PushPin, if (pinned) "Unpin" else "Pin", {
                CrashLog.note(if (pinned) "unpin group" else "pin group")
                AlbumPrefs.setPinned(entry.key, !pinned)
                onDismiss()
            }, sub = "Pinned albums come first".takeIf { !pinned }, trailing = null)
            MenuRow(Sym.Edit, "Rename", { step = GroupStep.Rename }, trailing = null)
            MenuRow(Sym.Image, "Change cover", { step = GroupStep.Cover }, trailing = null)
            MenuRow(Sym.Share, "Share as ZIP", {
                onDismiss()
                actions.shareZipParts(g.name, g.albums.map { ZipPart(it.name, it.itemsIn(media)) })
            }, sub = "A folder for each album", trailing = null)
            MenuRow(Sym.UnfoldMore, "Ungroup", {
                onDismiss()
                onGone()
                organise("ungroup") {
                    val snapshot = Collections.ungroup(g.id) ?: return@organise null
                    AlbumOps.Message("Ungrouped ${g.name}. Its albums are back in Albums.") {
                        organise("undo ungroup") {
                            Collections.undoUngroup(snapshot)
                            null
                        }
                    }
                }
            }, sub = "Its ${counted(g.albums.size, "album", "albums")} stay, on their own", trailing = null)
        }
        GroupStep.Rename -> GroupNameSheet("Rename group", g.name, "Rename", view.groups.filter { it !== entry }.map { it.name }, onDismiss) { name ->
            onDismiss()
            CrashLog.note("rename group")
            Collections.renameGroup(g.id, name)
        }
        GroupStep.Cover -> AlbumCoverSheet(entry, media, onDismiss)
    }
}

/** Asks for a group name. [confirm] stays off until [groupNameError] is happy; [taken] are the other groups' names. */
@Composable
private fun GroupNameSheet(title: String, initial: String, confirm: String, taken: List<String>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    // Starts with the old name selected, so typing replaces it
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val error = groupNameError(text.text, taken)
    val showError = error != null && text.text.isNotEmpty() // no "Enter a name" before anything is typed
    NameSheet(
        title, text, { text = it }, confirm, enabled = error == null, onDismiss = onDismiss, onConfirm = { if (error == null) onDone(text.text.trim()) },
        note = error.takeIf { showError }, isError = showError,
    )
}

/** An album card's or row's picture: its cover, or the collection or group icon when it has none yet. */
@Composable
fun BoxScope.EntryCover(e: AlbumEntry) {
    val cover = e.cover
    if (cover != null) Thumbnail(cover, Modifier.matchParentSize())
    else Icon(if (e.group != null) Sym.Folder else Sym.CollectionsBookmark, null, tint = AlbumTheme.colors.inkMuted)
}

// ---------- A group's page ----------

/**
 * A group's albums, pushed over the tabs like All albums: in the chosen order with pinned ones first, each opening as
 * usual, long-press for its sheet. More holds the group's own sheet. Closes itself once the group is gone (ungrouped, or
 * its last album taken out).
 */
@Composable
fun AlbumGroupScreen(
    id: Long,
    albums: List<Album>,
    collections: List<CollectionAlbum>,
    media: List<Media>,
    actions: AlbumActions,
    state: LazyGridState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val view = rememberAlbumsView(albums, collections, media)
    val entry = view.groups.find { it.group!!.id == id }
    val pinned by AlbumPrefs.pinned.collectAsState()
    val hidden by AlbumPrefs.hidden.collectAsState()
    val sort by AlbumPrefs.sort.collectAsState()
    var menu by remember { mutableStateOf<AlbumEntry?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    // After a moment for the albums to load, like an album that stops existing
    LaunchedEffect(entry == null) {
        if (entry == null) {
            delay(1_500)
            onBack()
        }
    }
    val g = entry?.group
    PushedGridPage(g?.name.orEmpty(), g?.let { counted(it.albums.size, "album", "albums") }, state, onBack, actions = {
        if (entry != null) AlbumIconButton(Sym.MoreVert, "More", { more = true })
    }) {
        fullRow("sort") { SectionHead(sort.order, action = "Sort", onAction = { sorting = true }) }
        albumCards(g?.albums.orEmpty(), pinned, hidden, onOpen, onOpenGroup = {}) { menu = it }
    }
    menu?.let { AlbumEntrySheet(it, view, collections, media, pinned, hidden, actions) { menu = null } }
    if (more && entry != null) GroupSheet(entry, view, media, actions, entry.key in pinned, onDismiss = { more = false }, onGone = onBack)
    if (sorting) AlbumSortSheet { sorting = false }
}
