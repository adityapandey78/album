package com.aditya.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The safe's Add page (photos, albums and people from the Library) and where Move out sends what leaves the safe.

/** The photos of the people picked, each once, and how many of them also show someone who wasn't picked. */
class PeoplePhotos<T>(val items: List<T>, val shared: Int)

/** [picked] and [others] are each person's photos: picked people's photos, each once, counting those [others] are in too. */
fun <T> photosOfPeople(picked: List<List<T>>, others: List<List<T>>, idOf: (T) -> Long): PeoplePhotos<T> {
    val seen = HashSet<Long>()
    val items = picked.flatten().filter { seen.add(idOf(it)) }
    val elsewhere = others.flatten().mapTo(HashSet(), idOf)
    return PeoplePhotos(items, items.count { idOf(it) in elsewhere })
}

/** Everyone but [picked] (clusters) whose face in a photo means it shows someone else: people Album shows, not pets. */
fun othersThan(all: List<PersonAlbum>, picked: Set<Long>) = all.filter { it.species == null && it.shown && it.cluster !in picked }.map { it.items }

/** The warning when [shared] of the photos moving also show other people, or null when none do. */
fun sharedNote(shared: Int) = when (shared) {
    0 -> null
    1 -> "1 photo also shows someone else. It moves too."
    else -> "${"%,d".format(shared)} photos also show other people. They move too."
}

/** Folders that can take every one of the items leaving: MediaStore keeps photos under DCIM and Pictures, videos under Movies too. */
fun <T> foldersFor(folders: List<T>, pathOf: (T) -> String, photos: Boolean, videos: Boolean) =
    folders.filter { (!photos || canHold(pathOf(it), false)) && (!videos || canHold(pathOf(it), true)) }

/** Why [name] can't be a new folder in [parent] ("Pictures/"), or null if it can. A folder that's there already is fine. */
fun newFolderError(parent: String, name: String): String? = albumNameError(name, emptyList())
    ?: "${name.trim()} is a system folder. Pick another name.".takeIf { folderKey(albumFolder(parent, name.trim())) in systemFolders }

/** What Original folder says for [items]: their one folder, Restored where Album saves when theirs can't take them, or each its own. */
private fun originalNote(items: List<SafeItem>, saving: SaveLocation): String {
    val folders = items.mapTo(HashSet()) { restorePath(it.path, it.isVideo) }
    if (folders.size != 1) return "Each to the folder it came from"
    return folders.first()?.trimEnd('/') ?: placeName(saving.inside("Restored"))
}

// The docked button and the gap under it, so the last row scrolls clear of it
private val AddDock = 56.dp + 20.dp + Space.s8

private enum class AddTab { Photos, Albums, People }

/**
 * The open safe's Add page, over it: Photos, the Library's grid ([photos]) with checks; Albums, folders ([albums]) and
 * [collections], each picking all its photos; People, each picking every photo they're in. Picks are kept across the
 * tabs, and the docked "Add N to safe" hands their photos (each once, among [media]) to [moveToSafe], whose ConfirmBar
 * warns when a person's photos also show someone else. The page closes ([onClose]) once the move begins.
 */
@Composable
internal fun AddToSafePage(
    photos: List<Media>,
    media: List<Media>,
    albums: List<Album>,
    collections: List<CollectionAlbum>,
    moveToSafe: MoveToSafe,
    onClose: () -> Unit,
) {
    val c = AlbumTheme.colors
    var tab by rememberSaveable { mutableStateOf(AddTab.Photos) }
    var photoIds by remember { mutableStateOf(emptySet<Long>()) }
    var folderIds by remember { mutableStateOf(emptySet<Long>()) }
    var collectionIds by remember { mutableStateOf(emptySet<Long>()) }
    var clusters by remember { mutableStateOf(emptySet<Long>()) }
    val all by People.albums.collectAsState()
    val people = remember(all) { shownPeople(all) }
    val shownCollections = remember(collections) { collections.filter { it.items.isNotEmpty() } }
    val fromPeople = remember(all, clusters) {
        photosOfPeople(all.filter { it.cluster in clusters }.map { it.items }, othersThan(all, clusters), Media::id)
    }
    // In the Library's order; photos that left it meanwhile drop out
    val chosen = remember(media, photoIds, folderIds, collectionIds, shownCollections, fromPeople) {
        val ids = HashSet(photoIds)
        shownCollections.filter { it.collection.id in collectionIds }.forEach { col -> col.items.mapTo(ids) { it.id } }
        fromPeople.items.mapTo(ids) { it.id }
        media.filter { it.id in ids || it.bucketId in folderIds }
    }
    val note = sharedNote(fromPeople.shared)
    val columns = rememberColumns("columns_photos") // the Library's, as the safe's grid
    val grid = rememberLazyGridState()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + AddDock + if (note != null) 56.dp else 0.dp

    fun <K> Set<K>.flip(k: K) = if (k in this) this - k else this + k

    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(c.canvas).pointerInput(Unit) {}) { // catches taps meant for the safe underneath
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.statusBarsPadding()) {
                CollapsedTopBar(
                    "Add to safe",
                    subtitle = if (chosen.isEmpty()) "Pick photos, albums or people" else "${itemCount(chosen.size)} selected",
                    onBack = onClose,
                )
            }
            Box(Modifier.padding(horizontal = Space.s4)) {
                Segmented(listOf(AddTab.Photos to "Photos", AddTab.Albums to "Albums", AddTab.People to "People"), tab) { tab = it }
            }
            val list = Modifier.weight(1f).fillMaxWidth()
            val padding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = bottom)
            when (tab) {
                AddTab.Photos -> MediaGrid(
                    photos, columns, grid, photoIds, { photoIds = photoIds.flip(it.id) }, { photoIds = it },
                    PaddingValues(bottom = bottom), list, selectMode = true,
                )
                AddTab.Albums -> LazyColumn(list, contentPadding = padding) {
                    if (albums.isEmpty() && shownCollections.isEmpty()) item { EmptyState(Sym.PhotoAlbum, "No albums yet", "Folders and collections show up here.") }
                    if (albums.isNotEmpty()) item(key = "folders") { AddHead("Folders") }
                    items(albums, key = { "f${it.id}" }) { a ->
                        PickRow(a.name, itemCount(a.count), a.id in folderIds, { folderIds = folderIds.flip(a.id) }) { Thumbnail(a.cover, Modifier.matchParentSize()) }
                    }
                    if (shownCollections.isNotEmpty()) item(key = "collections") { AddHead("Collections") }
                    items(shownCollections, key = { "c${it.collection.id}" }) { col ->
                        PickRow(col.name, itemCount(col.items.size), col.collection.id in collectionIds, { collectionIds = collectionIds.flip(col.collection.id) }) {
                            Thumbnail(col.items.first(), Modifier.matchParentSize())
                        }
                    }
                }
                AddTab.People -> LazyColumn(list, contentPadding = padding) {
                    if (people.isEmpty()) item { EmptyState(Sym.Face, "No people yet", "People show up here once Album has found faces in your photos.") }
                    items(people, key = { it.cluster }) { p ->
                        val count = counted(p.items.size, "photo", "photos")
                        PickRow(personTitle(p), if (p.species != null) "Pet · $count" else count, p.cluster in clusters, { clusters = clusters.flip(p.cluster) }, round = true) {
                            FaceImage(p.cover, Modifier.matchParentSize())
                        }
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = Space.s4, end = Space.s4, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            note?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(Space.radiusMd)).background(c.surface, RoundedCornerShape(Space.radiusMd))
                        .padding(horizontal = Space.s4, vertical = Space.s3),
                    style = AlbumTheme.type.caption, color = c.inkMuted,
                )
            }
            AlbumButton(
                if (chosen.isEmpty()) "Add to safe" else "Add ${"%,d".format(chosen.size)} to safe",
                {
                    CrashLog.note("safe add ${chosen.size}")
                    moveToSafe(chosen, note = note, onStart = onClose)
                },
                Modifier.fillMaxWidth().heightIn(min = 56.dp).shadow(10.dp, CircleShape), ButtonVariant.Primary, Sym.Lock,
                enabled = chosen.isNotEmpty(),
            )
        }
    }
}

/** A list heading on the Add page's Albums tab. */
@Composable
private fun AddHead(title: String) =
    Text(title, Modifier.padding(top = Space.s4, bottom = Space.s2), style = AlbumTheme.type.titleMd, color = AlbumTheme.colors.ink)

private enum class OutStep { Where, Folders, Naming }

/**
 * Move out of the safe, as sheets: Original folder (each back where it came from) or Choose a folder, one of [albums]
 * that can take all of [items], or a New folder named here, in the folder Album saves to. [onMove] gets the place,
 * null for the original folders.
 */
@Composable
internal fun MoveOutSheet(items: List<SafeItem>, albums: List<Album>, onDismiss: () -> Unit, onMove: (SaveLocation?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SavePrefs.load(context)
    val saving by SavePrefs.location.collectAsState()
    var step by remember { mutableStateOf(OutStep.Where) }
    val folders = remember(albums, items) { foldersFor(albums, { it.cover.path }, items.any { !it.isVideo }, items.any { it.isVideo }) }

    // Copies go to the folder's own storage; an SD card is named as Android names it
    fun pick(a: Album) = scope.launch {
        val path = a.cover.path.let { if (it.endsWith('/')) it else "$it/" }
        val to = withContext(Dispatchers.IO) {
            val volume = volumeOf(context.contentResolver, a.cover.uri)
            if (volume == PHONE_STORAGE) SaveLocation(folder = path)
            else SaveLocation(volume, path, storages(context).find { it.volume == volume }?.label ?: "SD card")
        }
        onMove(to)
    }

    when (step) {
        OutStep.Where -> Sheet(onDismiss, "Move ${itemCount(items.size)} out") {
            MenuRow(Sym.History, "Original folder", { onMove(null) }, sub = originalNote(items, saving), trailing = null)
            MenuRow(Sym.Folder, "Choose a folder…", { step = OutStep.Folders }, sub = "One of your folders, or a new one")
        }
        OutStep.Folders -> Sheet(onDismiss, "Move ${itemCount(items.size)} to") {
            LazyColumn {
                item(key = "new") { MenuRow(Sym.CreateNewFolder, "New folder…", { step = OutStep.Naming }, sub = "In ${placeName(saving)}", trailing = null) }
                items(folders, key = { it.id }) { a ->
                    CoverRow(a.name, a.cover.path.trimEnd('/'), { pick(a) }) { Thumbnail(a.cover, Modifier.matchParentSize()) }
                }
            }
        }
        OutStep.Naming -> {
            var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
            val name = text.text.trim()
            val error = newFolderError(saving.folder, name)
            val showError = error != null && name.isNotEmpty()
            NameSheet(
                "New folder", text, { text = it }, "Move here", enabled = error == null, onDismiss = { step = OutStep.Folders },
                onConfirm = {
                    if (error == null) scope.launch { onMove(withContext(Dispatchers.IO) { SavePrefs.target(context).inside(name) }) }
                },
                note = if (showError) error else "Goes in ${placeName(saving.inside(name.ifEmpty { "…" }))}",
                isError = showError, placeholder = "Folder name",
            )
        }
    }
}
