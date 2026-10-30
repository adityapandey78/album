package com.aditya.album

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Album groups ("Goa" holding "Day 1" to "Day 4") and album covers. Both live in collections.db with the collections,
// under the keys pins use, so no file moves on disk. One level deep: a group holds albums, never groups. Nesting would
// need a tree in every album list and a way back up from each level, for little gain over a second group.

/** Groups open through ids in their own band, above collections (2^48), so a group's card key never clashes with an album's. */
private const val GROUP_IDS = 1L shl 52

/** What a group's pin is saved under. Folder keys end in "/" and collection keys start "collection:", so none meet. */
fun groupKey(id: Long) = "group:$id"

fun groupAlbumId(id: Long) = GROUP_IDS + id

/** The group behind an album id (its photos page, from See all), or null for any other album. */
fun groupIdOf(albumId: Long): Long? = (albumId - GROUP_IDS).takeIf { albumId >= GROUP_IDS }

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

/** What Favourites' cover is saved under. Favourites is never pinned or grouped: it always leads the albums. */
const val FAVOURITES_KEY = "special:favourites"

/** Favourites as an album card: [items] are the favourites, newest first. */
fun favouritesEntry(items: List<Media>, cover: Media? = items.firstOrNull()) = AlbumEntry(
    Special.Favourites.id, FAVOURITES_KEY, Special.Favourites.title, items.size, items.firstOrNull()?.taken ?: 0, special = Special.Favourites, cover = cover,
)

/**
 * The Albums section and All albums: [top] holds the albums in no group and a card for each group, in [albumEntries]
 * order; [groups] are those groups' cards. A group shows while one of its albums does, so one whose albums are all out of
 * sight (an SD card taken out, a folder in Hidden items) hides with them and comes back with them.
 */
class AlbumsView(val top: List<AlbumEntry>, val groups: List<AlbumEntry>) {
    fun group(id: Long): GroupAlbum? = groups.firstNotNullOfOrNull { e -> e.group?.takeIf { it.id == id } }

    /** The group card of the group album [key] is in, or null. */
    fun groupOf(key: String): AlbumEntry? = groups.find { e -> e.group!!.albums.any { it.key == key } }

    /** The album with album id [id], in a group or not. Null for a group's card. */
    fun album(id: Long): AlbumEntry? = top.find { it.id == id && it.group == null } ?: groups.firstNotNullOfOrNull { g -> g.group!!.albums.find { it.id == id } }

    /** Albums that can go in a group, those on their own first: every folder and collection except the ones in group [except]. */
    fun groupable(except: Long?): List<AlbumEntry> =
        top.filter { it.group == null && it.special == null } + groups.filter { it.group!!.id != except }.flatMap { it.group!!.albums }
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
 * [automatic] one (a folder's or collection's newest photo), like a person's cover face.
 */
fun <T : Any> pickCover(chosen: Long?, find: (Long) -> T?, belongs: (T) -> Boolean, automatic: T?): T? =
    chosen?.let(find)?.takeIf(belongs) ?: automatic

/** Whether [m] is in this album: a folder's file, a collection's item, a favourite, or any of those in one of a group's albums. */
fun AlbumEntry.holds(m: Media): Boolean = when {
    folder != null -> m.bucketId == folder.id
    collection != null -> collection.items.any { it.id == m.id }
    special != null -> m.isIn(special.id)
    else -> group?.albums?.any { it.holds(m) } == true
}

/** This album's photos and videos, newest first. A group's albums' together, each item once. [media] is every one Album shows. */
fun AlbumEntry.itemsIn(media: List<Media>): List<Media> = when {
    folder != null -> media.filter { it.bucketId == folder.id }
    collection != null -> collection.items
    special != null -> media.filter { it.isIn(special.id) }
    else -> group?.albums?.flatMap { it.itemsIn(media) }?.distinctBy { it.id }?.sortedByDescending { it.taken }.orEmpty()
}

/** When this album last got a photo (epoch millis): a folder's or collection's newest item. Null when it has none. */
private val AlbumEntry.newest: Long? get() = folder?.cover?.taken ?: collection?.items?.firstOrNull()?.taken

/**
 * Every folder and collection, grouped and with their chosen covers, for Collections, All albums and a group's page.
 * Favourites leads, always: it's where the heart in the viewer puts things, so it never sinks under the sort.
 */
@Composable
fun rememberAlbumsView(albums: List<Album>, collections: List<CollectionAlbum>, media: List<Media>): AlbumsView {
    val organisation by Collections.organisation.collectAsState()
    val pinned by AlbumPrefs.pinned.collectAsState()
    val sort by AlbumPrefs.sort.collectAsState()
    val byId = remember(media) { media.associateBy { it.id } }
    val favourites = remember(media) { media.filter { it.isFavorite } }
    return remember(albums, collections, organisation, pinned, sort, byId, favourites) {
        val o = organisation ?: return@remember AlbumsView(emptyList(), emptyList()) // a moment at launch
        val chosen = o.covers.associate { it.albumKey to it.mediaId }
        val entries = albums.map { a -> folderEntry(a, pickCover(chosen[a.key], byId::get, { it.bucketId == a.id }, a.cover) ?: a.cover) } +
            collections.map { c ->
                collectionEntry(c, pickCover(chosen[collectionKey(c.collection.id)], { id -> c.items.find { it.id == id } }, { true }, c.items.firstOrNull()))
            }
        val view = albumsView(entries, o.groups, o.members, sort, pinned)
        val favourite = favouritesEntry(favourites, pickCover(chosen[FAVOURITES_KEY], byId::get, { it.isFavorite }, favourites.firstOrNull()))
        AlbumsView(listOf(favourite) + view.top, view.groups)
    }
}

private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM")
private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy")
private val weekdayDay = DateTimeFormatter.ofPattern("EEE, d MMM")

/**
 * The days a group's photos span, from epoch day [first] to [last], with an en dash as the design writes it:
 * "12 Oct 2026", "12–15 Oct 2026", "29 Oct–2 Nov 2026", or across years "Dec 2025–Jan 2026".
 */
fun dateSpan(first: Long, last: Long): String {
    val a = LocalDate.ofEpochDay(first)
    val b = LocalDate.ofEpochDay(last)
    return when {
        a == b -> a.format(dayMonthYear)
        a.month == b.month && a.year == b.year -> "${a.dayOfMonth}–${b.format(dayMonthYear)}"
        a.year == b.year -> "${a.format(dayMonth)}–${b.format(dayMonthYear)}"
        else -> "${a.format(monthYear)}–${b.format(monthYear)}"
    }
}

/** How many [items] there are in the words the design uses: "36 photos", or "36 items" once videos are among them. */
fun photosOrItems(items: List<Media>) = if (items.any { it.isVideo }) itemCount(items.size) else counted(items.size, "photo", "photos")

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

/** How the album pickers name an album in a group, "Goa · Day 1", so two "Day 1"s tell apart. [group] is null for the rest. */
fun pickerName(name: String, group: String?) = if (group == null) name else "$group · $name"

/** Takes album [entry] out of its group, with Undo. */
fun takeOutOfGroup(entry: AlbumEntry) = moveAlbum(entry, "${entry.name} is back with your albums") { Collections.moveToGroup(entry.key, null) }

private fun albumsWord(n: Int) = counted(n, "album", "albums")

// ---------- Sheets ----------

/**
 * An album sheet's group rows: Move to a group, or to another one when it's in [group] already ([onMove] opens
 * [GroupPicker]), and Take out of the group ([onTakeOut]).
 */
@Composable
fun GroupMoveRows(group: String?, onMove: () -> Unit, onTakeOut: () -> Unit) {
    MenuRow(Sym.DriveFileMove, if (group == null) "Move to a group" else "Move to another group", onMove, sub = group?.let { "Now in $it" })
    if (group != null) MenuRow(Sym.FolderCopy, "Take out of the group", onTakeOut, trailing = null)
}

/** Picks the group album [entry] moves to: one of the groups (its own is checked), or a new one named here. Undo puts it back. */
@Composable
fun GroupPicker(entry: AlbumEntry, view: AlbumsView, onDismiss: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    val current = view.groupOf(entry.key)
    if (naming) {
        GroupNameSheet("New album group", "", "Create and move", view.groups.map { it.name }, onDismiss) { name ->
            onDismiss()
            moveAlbum(entry, "Moved ${entry.name} to $name") { Collections.newGroup(name, entry.key) }
        }
        return
    }
    Sheet(onDismiss, "Move ${entry.name} to") {
        LazyColumn {
            items(view.groups, key = { it.id }) { g ->
                val here = g === current
                MenuRow(
                    Sym.Stacks, g.name,
                    {
                        onDismiss()
                        if (!here) moveAlbum(entry, "Moved ${entry.name} to ${g.name}") { Collections.moveToGroup(entry.key, g.group.id) }
                    },
                    Modifier.semantics { selected = here },
                    sub = albumsWord(g.group!!.albums.size),
                    trailing = if (here) Sym.Check else null,
                )
            }
            item(key = "new") { MenuRow(Sym.Add, "New group", { naming = true }) }
        }
    }
}

/**
 * Change cover for an album or collection: its photos, newest first, the current cover checked. Tapping one keeps it as
 * the cover until it leaves the album; Choose automatically (shown once one is picked) hands the choice back to Album.
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
        if (chosen) MenuRow(Sym.Refresh, "Choose automatically", { pick(null) }, sub = "Album shows the newest photo", trailing = null)
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

private enum class GroupStep { Menu, Add, Rename }

/**
 * A group's sheet, from a long-press on its card or More on its page: pin, add albums, rename, share as ZIP (a folder per
 * album) and Ungroup, which only takes the group away; its albums stay, on their own, and Undo brings it back. [onGone]
 * runs after Ungroup.
 */
@Composable
fun GroupSheet(entry: AlbumEntry, view: AlbumsView, media: List<Media>, actions: AlbumActions, pinned: Boolean, onDismiss: () -> Unit, onGone: () -> Unit = {}) {
    val g = entry.group ?: return
    var step by rememberSaveable { mutableStateOf(GroupStep.Menu) }
    when (step) {
        GroupStep.Menu -> Sheet(onDismiss, g.name) {
            MenuRow(Sym.PushPin, if (pinned) "Unpin" else "Pin to the top", {
                CrashLog.note(if (pinned) "unpin group" else "pin group")
                AlbumPrefs.setPinned(entry.key, !pinned)
                onDismiss()
            }, trailing = null)
            MenuRow(Sym.Add, "Add albums", { step = GroupStep.Add })
            MenuRow(Sym.Edit, "Rename group", { step = GroupStep.Rename })
            MenuRow(Sym.Share, "Share as ZIP", {
                onDismiss()
                actions.shareZipParts(g.name, g.albums.map { ZipPart(it.name, it.itemsIn(media)) })
            }, sub = "A folder for each album", trailing = null)
            MenuRow(Sym.FolderCopy, "Ungroup", {
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
            }, sub = "Its ${albumsWord(g.albums.size)} stay, just not grouped", trailing = null)
        }
        GroupStep.Add -> AddToGroupSheet(g, view, onDismiss)
        GroupStep.Rename -> GroupNameSheet("Rename group", g.name, "Rename", view.groups.filter { it !== entry }.map { it.name }, onDismiss) { name ->
            onDismiss()
            CrashLog.note("rename group")
            Collections.renameGroup(g.id, name)
        }
    }
}

/** Picks albums to move into group [g]: any not in it already, including ones in other groups. */
@Composable
private fun AddToGroupSheet(g: GroupAlbum, view: AlbumsView, onDismiss: () -> Unit) {
    PickAlbumsSheet("Add to ${g.name}", null, "Add", view.groupable(except = g.id), view, onDismiss) { picked ->
        onDismiss()
        organise("add to group") {
            Collections.addToGroup(picked.map { it.key }, g.id)
            AlbumOps.Message("Added ${albumsWord(picked.size)} to ${g.name}")
        }
    }
}

/** New album group, from Collections' More and All albums: name it, then pick its albums from [view]. */
@Composable
fun NewGroupSheets(view: AlbumsView, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    val named = name
    if (named == null) {
        GroupNameSheet(
            "New album group", "", "Next", view.groups.map { it.name }, onDismiss,
            hint = "A group keeps related albums together and shows as one card.",
        ) { name = it }
        return
    }
    PickAlbumsSheet("Albums for $named", "Pick the albums to keep together.", "Create group", view.groupable(except = null), view, onDismiss) { picked ->
        onDismiss()
        organise("new group") {
            Collections.groupAlbums(named, picked.map { it.key })
            AlbumOps.Message("Grouped ${albumsWord(picked.size)} in $named")
        }
    }
}

/**
 * Picks albums from [choices] with a check on each: its cover, name, count and the group it's in now ([view]), then
 * Cancel and [action] with how many are picked.
 */
@Composable
private fun PickAlbumsSheet(
    title: String,
    note: String?,
    action: String,
    choices: List<AlbumEntry>,
    view: AlbumsView,
    onDismiss: () -> Unit,
    onDone: (List<AlbumEntry>) -> Unit,
) {
    val c = AlbumTheme.colors
    val haptics = rememberHaptics()
    var picked by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val tallest = (LocalConfiguration.current.screenHeightDp * 0.46f).dp
    Sheet(onDismiss, title) {
        note?.let { Text(it, style = AlbumTheme.type.body, color = c.inkMuted) }
        if (choices.isEmpty()) {
            Text("No other albums yet.", Modifier.padding(vertical = Space.s4), style = AlbumTheme.type.body, color = c.inkMuted)
        } else {
            LazyColumn(Modifier.heightIn(max = tallest).padding(vertical = Space.s2)) {
                items(choices, key = { it.id }) { e ->
                    val on = e.id in picked
                    PickRow(e, view.groupOf(e.key)?.name, on) {
                        picked = if (on) picked - e.id else picked + e.id
                        haptics.lightTick()
                    }
                }
            }
        }
        SheetButtons(if (picked.isEmpty()) action else "$action ${picked.size}", picked.isNotEmpty(), onDismiss) {
            onDone(choices.filter { it.id in picked })
        }
    }
}

/** An album to pick: its cover, name, count and the group it's in ([group]), and a check. The whole row toggles. */
@Composable
private fun PickRow(e: AlbumEntry, group: String?, on: Boolean, onToggle: () -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(on, role = Role.Checkbox, onValueChange = { onToggle() }).padding(vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Space.target).clip(RoundedCornerShape(10.dp)).background(c.surfaceHigh), contentAlignment = Alignment.Center) { EntryCover(e) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.name, style = AlbumTheme.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("%,d".format(e.count) + (group?.let { " · in $it" } ?: ""), style = AlbumTheme.type.caption, color = c.inkMuted, maxLines = 1)
        }
        SelectCheck(on, onSurface = true)
    }
}

/**
 * Asks for a group name, with [hint] above the field. [confirm] stays off until [groupNameError] is happy; [taken] are
 * the other groups' names.
 */
@Composable
private fun GroupNameSheet(
    title: String,
    initial: String,
    confirm: String,
    taken: List<String>,
    onDismiss: () -> Unit,
    hint: String? = null,
    onDone: (String) -> Unit,
) {
    // Starts with the old name selected, so typing replaces it
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val error = groupNameError(text.text, taken)
    val showError = error != null && text.text.isNotEmpty() // no "Enter a name" before anything is typed
    NameSheet(
        title, text, { text = it }, confirm, enabled = error == null, onDismiss = onDismiss, onConfirm = { if (error == null) onDone(text.text.trim()) },
        note = error.takeIf { showError }, isError = showError, hint = hint, placeholder = if (initial.isEmpty()) "Group name, like Goa" else "Group name",
    )
}

/** An album card's or row's picture: its cover, or the album's icon when it has none yet. */
@Composable
fun BoxScope.EntryCover(e: AlbumEntry) {
    val cover = e.cover
    if (cover != null) Thumbnail(cover, Modifier.matchParentSize())
    else Icon(if (e.special != null) Sym.Favorite else if (e.group != null) Sym.Stacks else Sym.CollectionsBookmark, null, tint = AlbumTheme.colors.inkMuted)
}

/**
 * A group's cover, like an app folder: its albums' covers in two rows of two on surface-high, the fourth cell "+N" when
 * more don't fit, empty cells outlined.
 */
@Composable
fun BoxScope.FolderCover(albums: List<AlbumEntry>) {
    val c = AlbumTheme.colors
    val more = albums.size > 4
    val cells = if (more) albums.take(3) else albums
    val shape = RoundedCornerShape(9.dp)
    Column(Modifier.matchParentSize().padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (row in 0..1) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    val cell = Modifier.weight(1f).fillMaxHeight().clip(shape)
                    when {
                        i == 3 && more -> Box(cell.background(c.surface), contentAlignment = Alignment.Center) {
                            Text("+${albums.size - 3}", style = AlbumTheme.type.meta, color = c.ink)
                        }
                        i < cells.size -> Box(cell.background(c.line)) { cells[i].cover?.let { Thumbnail(it, Modifier.matchParentSize()) } }
                        else -> Box(cell.border(1.5.dp, c.line, shape))
                    }
                }
            }
        }
    }
}

/** The mark on a group's folder: a stack on a flash disc, ringed with canvas, sitting over the bottom-right corner. */
@Composable
fun BoxScope.FolderMark() {
    val c = AlbumTheme.colors
    Box(
        Modifier.align(Alignment.BottomEnd).offset(x = 7.dp, y = 7.dp).size(30.dp).background(c.canvas, CircleShape).padding(2.dp).background(c.flash, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Sym.Stacks, null, filled = true, size = Space.iconSm, tint = c.onFlash) }
}

// ---------- A group's page ----------

/**
 * A group's page, pushed over the tabs: its covers in a small collage with the days its photos span, its albums (each
 * opening as usual through [onOpen], long-press for its sheet) and an Add album card, then every photo in the group
 * ([onOpen] with the group's own album id) and Play as a memory ([onPlay]). More holds the group's own sheet. Closes
 * itself once the group is gone (ungrouped, or its last album taken out).
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
    onPlay: (title: String, eyebrow: String, items: List<Media>) -> Unit,
) {
    val view = rememberAlbumsView(albums, collections, media)
    val entry = view.groups.find { it.group!!.id == id }
    val pinned by AlbumPrefs.pinned.collectAsState()
    val hidden by AlbumPrefs.hidden.collectAsState()
    var menu by remember { mutableStateOf<AlbumEntry?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // After a moment for the albums to load, like an album that stops existing
    LaunchedEffect(entry == null) {
        if (entry == null) {
            delay(1_500)
            onBack()
        }
    }
    val g = entry?.group
    val items = remember(entry, media) { entry?.itemsIn(media).orEmpty() }
    val span = remember(items) { if (items.isEmpty()) null else dateSpan(items.last().day, items.first().day) }
    PushedGridPage(
        g?.name.orEmpty(), g?.let { "${albumsWord(it.albums.size)} · ${photosOrItems(items)}" }, state, onBack, collapsed = true,
        actions = { if (entry != null) AlbumIconButton(Sym.MoreVert, "Group options", { more = true }) },
    ) {
        if (g == null) return@PushedGridPage
        fullRow("head") { GroupHead(g, span) }
        albumCards(g.albums, pinned, hidden, onOpen, onOpenGroup = {}, sub = ::dayAndCount) { menu = it }
        item(key = "add") { AddAlbumCard(g.name, Modifier.padding(bottom = Space.s4)) { adding = true } }
        fullRow("rows") {
            Group(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s2)) {
                GroupRow("See all ${photosOrItems(items)}", { onOpen(groupAlbumId(g.id)) }, icon = Sym.PhotoLibrary, sub = "Every album in this group, by day")
                // Memories play photos, oldest first, so a group with only videos has nothing to play
                val photos = items.filter { !it.isVideo }
                if (photos.isNotEmpty()) GroupRow("Play as a memory", {
                    CrashLog.note("play group")
                    onPlay(g.name, "Album group", spread(photos.asReversed(), STORY_LENGTH))
                }, icon = Sym.Slideshow)
            }
        }
    }
    menu?.let { AlbumEntrySheet(it, view, collections, media, pinned, hidden, actions) { menu = null } }
    if (more && entry != null) GroupSheet(entry, view, media, actions, entry.key in pinned, onDismiss = { more = false }, onGone = onBack)
    if (adding && g != null) AddToGroupSheet(g, view) { adding = false }
}

/** An album in a group's page: the day it last got a photo and its count, "Sat, 12 Oct · 23". */
private fun dayAndCount(e: AlbumEntry): String {
    val day = e.newest?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(weekdayDay) }
    return listOfNotNull(day, "%,d".format(e.count)).joinToString(" · ")
}

/** The top of a group's page: up to four album covers in a small collage, the Album group badge, its name and [span]. */
@Composable
private fun GroupHead(g: GroupAlbum, span: String?) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s6),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(Space.radiusMd)).background(c.surfaceHigh).padding(5.dp).clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            g.albums.take(4).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEach { a ->
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(c.line)) { a.cover?.let { Thumbnail(it, Modifier.matchParentSize()) } }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Badge("Album group", icon = Sym.Stacks)
            Text(g.name, style = AlbumTheme.type.titleLg, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            span?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
        }
    }
}

/** The last card on a group's page: an outlined square with a plus, to add albums to the group called [group]. */
@Composable
private fun AddAlbumCard(group: String, modifier: Modifier, onClick: () -> Unit) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusMd)
    AlbumCard("Add album", emptyList(), "Add albums to $group", onClick, modifier) {
        Box(Modifier.matchParentSize().background(c.canvas).border(1.5.dp, c.lineStrong, shape), contentAlignment = Alignment.Center) {
            Icon(Sym.Add, null, tint = c.ink)
        }
    }
}

/** A group's photos as one album, for its See all: album id [id] from [groupAlbumId]. Null for any other album. */
@Composable
fun rememberGroupPhotos(id: Long, albums: List<Album>, collections: List<CollectionAlbum>, media: List<Media>): SmartAlbum? {
    val groupId = groupIdOf(id) ?: return null
    val g = rememberAlbumsView(albums, collections, media).groups.find { it.group!!.id == groupId } ?: return null
    return remember(g, media) { SmartAlbum(g.name, g.itemsIn(media), "Nothing here yet. Add albums to this group from its page.") }
}
