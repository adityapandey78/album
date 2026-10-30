package com.aditya.album

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Collections open through the same album id (a Long) as folders and [Special] albums, so navigation, saved state and
 * CollectionsScreen.onOpen work unchanged. Their ids start at 2^48: folder ids are BUCKET_IDs (32-bit hashes, so the Int range)
 * and Special ids are small multiples of 2^32, so neither can reach this range.
 */
private const val COLLECTION_IDS = 1L shl 48

fun albumIdOf(collectionId: Long) = COLLECTION_IDS + collectionId

/** The collection behind an album id, or null for a folder or a [Special] album. */
fun collectionIdOf(albumId: Long): Long? = if (albumId >= COLLECTION_IDS) albumId - COLLECTION_IDS else null

/** What a pinned collection is saved as in [AlbumPrefs.pinned]. Folder keys are paths ending in "/", so the two never meet. */
fun collectionKey(collectionId: Long) = "collection:$collectionId"

/** Each collection's items that are still on the phone, in [media]'s order (newest first), keyed by collection id. */
fun <T> membersOf(items: List<CollectionItem>, media: List<T>, idOf: (T) -> Long): Map<Long, List<T>> {
    val collectionsOf = items.groupBy({ it.mediaId }, { it.collectionId })
    val out = HashMap<Long, MutableList<T>>()
    for (m in media) collectionsOf[idOf(m)]?.forEach { out.getOrPut(it) { ArrayList() } += m }
    return out
}

/** Why [name] can't name a collection, or null if it can. Names are trimmed, and [taken] (the other collections) compared ignoring case. */
fun collectionNameError(name: String, taken: Collection<String>): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Enter a name"
        taken.any { it.trim().equals(trimmed, ignoreCase = true) } -> "You already have a collection with this name"
        else -> null
    }
}

fun addedMessage(added: Int, name: String) = if (added == 0) "Already in $name" else "Added $added to $name"

const val EMPTY_COLLECTION = "Nothing here yet. Long-press photos in the Library or any album, then tap Album and Add to collection."

/** A collection with its items that are still on the phone, newest first. */
data class CollectionAlbum(val collection: MediaCollection, val items: List<Media>) {
    val albumId get() = albumIdOf(collection.id)
    val name get() = collection.name
}

/** Album groups, their albums and chosen covers, as collections.db has them. */
class Organisation(val groups: List<AlbumGroup>, val members: List<GroupMember>, val covers: List<AlbumCover>) {
    /** The name of the group each grouped album is in, by album key. */
    fun groupNames(): Map<String, String> {
        val names = groups.associate { it.id to it.name }
        return members.mapNotNull { m -> names[m.groupId]?.let { m.albumKey to it } }.toMap()
    }
}

/** The user's collections, kept in their own database (collections.db) because, unlike the smart index, they can't be rebuilt. */
object Collections {
    private lateinit var app: Context
    private val dao by lazy { Room.databaseBuilder(app, CollectionsDb::class.java, "collections.db").build().dao() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Single instances, so collectAsState keeps one subscription
    val list by lazy { dao.collections() }
    val items by lazy { dao.items() }

    /**
     * Call once from Application.onCreate. Items whose photo is gone are never deleted from the database: they just
     * don't show (see membersOf). A photo can vanish only for a while (SD card out, a folder hidden by another app,
     * MediaStore rescanning), and MediaStore never reuses an id, so keeping its row is safe and costs a few bytes.
     */
    fun start(context: Context) {
        app = context.applicationContext
    }

    // Writes run in this scope, so rotating or leaving a screen mid-write never drops one.
    private suspend fun <T> write(block: suspend (CollectionsDao) -> T): T = scope.async { block(dao) }.await()

    suspend fun create(name: String): Long = write { dao ->
        val now = System.currentTimeMillis()
        dao.insert(MediaCollection(name = name.trim(), created = now, updated = now))
    }

    /** Adds [mediaIds] to collection [id], or to a new collection called [name] when [id] is null. How many were new. */
    suspend fun add(id: Long?, name: String, mediaIds: List<Long>): Int = write { dao ->
        val now = System.currentTimeMillis()
        val target = id ?: dao.insert(MediaCollection(name = name.trim(), created = now, updated = now))
        dao.add(target, mediaIds.map { CollectionItem(target, it, now) }, now)
    }

    /** The rows removed, for [restore]. The photos themselves are never touched. */
    suspend fun remove(id: Long, mediaIds: List<Long>): List<CollectionItem> = write { it.remove(id, mediaIds, System.currentTimeMillis()) }

    /** Undo for [remove]: puts the rows back with their original added times. */
    suspend fun restore(items: List<CollectionItem>) {
        val id = items.firstOrNull()?.collectionId ?: return
        write { it.add(id, items, System.currentTimeMillis()) }
    }

    fun rename(id: Long, name: String) {
        scope.launch { safely { dao.rename(id, name.trim()) } }
    }

    fun delete(id: Long) {
        scope.launch { safely { dao.delete(id) } }
    }

    // ---------- Album groups and covers. Writes return once saved. ----------
    // A deleted group's pin stays in AlbumPrefs: group ids are never reused, and Undo brings the group back pinned.

    /** Groups, who's in them and chosen covers. Null until first read, so albums never jump into their groups on screen. */
    val organisation: StateFlow<Organisation?> by lazy {
        combine(dao.groups(), dao.members(), dao.covers(), ::Organisation)
            .catch { e ->
                CrashLog.note("organisation read failed: $e")
                emit(Organisation(emptyList(), emptyList(), emptyList())) // albums still show, just ungrouped
            }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** A new group called [name] holding album [key]. */
    suspend fun newGroup(name: String, key: String): GroupMove = write { it.newGroup(name.trim(), key, System.currentTimeMillis()) }

    /** Moves album [key] into group [id], or out of its group when [id] is null. */
    suspend fun moveToGroup(key: String, id: Long?): GroupMove = write { it.moveToGroup(key, id, System.currentTimeMillis()) }

    /** A new group called [name] holding albums [keys], from New album group. Returns its id. */
    suspend fun groupAlbums(name: String, keys: List<String>): Long = write { it.newGroupOf(name.trim(), keys, System.currentTimeMillis()) }

    /** Moves albums [keys] into group [id], from a group's Add albums. */
    suspend fun addToGroup(keys: List<String>, id: Long) = write { it.addToGroup(keys, id, System.currentTimeMillis()) }

    /** Undo for [newGroup] and [moveToGroup]. */
    suspend fun moveBack(key: String, move: GroupMove) = write { it.moveBack(key, move.before) }

    fun renameGroup(id: Long, name: String) {
        scope.launch { safely { dao.renameGroup(id, name.trim()) } }
    }

    /** Deletes group [id] (never its albums) and returns it as it was, for [undoUngroup]. */
    suspend fun ungroup(id: Long): GroupSnapshot? = write { it.ungroup(id) }

    suspend fun undoUngroup(snapshot: GroupSnapshot) = write { it.undoUngroup(snapshot) }

    /** [mediaId] becomes the cover of the album, collection or group with [key]; null hands the choice back to Album. */
    suspend fun setCover(key: String, mediaId: Long?) = write { dao -> if (mediaId == null) dao.dropCover(key) else dao.putCover(AlbumCover(key, mediaId)) }

    /** A renamed folder keeps its group and cover ([old] and [new] are [folderKey]s). */
    suspend fun rekey(old: String, new: String) = write { it.rekey(old, new) }

    // ---------- Dates and places the user fixed (see FixDetails.kt) ----------

    val fixes by lazy { dao.fixes() }

    suspend fun allFixes(): List<MediaFix> = write { it.allFixes() }

    suspend fun fixesOf(ids: List<Long>): List<MediaFix> = write { it.fixesBefore(ids) }

    suspend fun putFixes(fixes: List<MediaFix>) = write { it.putFixes(fixes) }

    /** Undo: the rows of [ids] become [before] again. */
    suspend fun restoreFixes(ids: List<Long>, before: List<MediaFix>) = write { it.restoreFixes(ids, before) }
}

private const val COULDNT_SAVE = "Couldn't save that change to your collections"

/** Runs a collection change; a database error (full storage, say) is logged and returns null instead of crashing Album. */
private suspend fun <T> safely(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    CrashLog.note("collections write failed: $e")
    null
}

/** Every collection, most recently used first, joined with [media] (newest first) so photos deleted from the phone drop out. */
@Composable
fun rememberCollections(media: List<Media>): List<CollectionAlbum> {
    val collections by Collections.list.collectAsState(emptyList())
    val items by Collections.items.collectAsState(emptyList())
    return remember(collections, items, media) {
        val members = membersOf(items, media) { it.id }
        collections.map { CollectionAlbum(it, members[it.id].orEmpty()) }
    }
}

/** Returns a function that adds media to collection id (or to a new one called name when id is null), then says so in [snackbar]. */
@Composable
fun rememberAddToCollection(snackbar: SnackbarHostState): (id: Long?, name: String, mediaIds: List<Long>) -> Unit {
    val scope = rememberCoroutineScope()
    return { id, name, mediaIds ->
        CrashLog.note("add ${mediaIds.size} to ${if (id == null) "new " else ""}collection")
        scope.launch {
            val added = safely { Collections.add(id, name, mediaIds) }
            snackbar.showSnackbar(added?.let { addedMessage(it, name) } ?: COULDNT_SAVE)
        }
    }
}

/** Returns a function that removes media from a collection (never from the phone), then offers Undo in [snackbar]. */
@Composable
fun rememberRemoveFromCollection(snackbar: SnackbarHostState): (CollectionAlbum, List<Long>) -> Unit {
    val scope = rememberCoroutineScope()
    return { c, mediaIds ->
        CrashLog.note("remove ${mediaIds.size} from collection")
        scope.launch {
            val removed = safely { Collections.remove(c.collection.id, mediaIds) } ?: return@launch run { snackbar.showSnackbar(COULDNT_SAVE) }
            if (removed.isEmpty()) return@launch
            val undo = snackbar.showSnackbar("Removed ${removed.size} from ${c.name}, not from your phone", "Undo", duration = SnackbarDuration.Long)
            if (undo == SnackbarResult.ActionPerformed) {
                CrashLog.note("undo remove ${removed.size}")
                safely { Collections.restore(removed) }
            }
        }
    }
}

/**
 * Long-press sheet of a collection in Collections: pin, rename, change cover ([onCover]), its group ([group] names the one
 * it's in; [onGroup] picks another, [onTakeOut] takes it out), share as ZIP ([onShare]) or delete it. Deleting asks first;
 * the photos are never touched. [onDismiss] runs when it's finished, whichever way.
 */
@Composable
fun CollectionSheet(
    c: CollectionAlbum,
    all: List<CollectionAlbum>,
    pinned: Boolean,
    group: String?,
    onCover: () -> Unit,
    onGroup: () -> Unit,
    onTakeOut: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(CollectionStep.Menu) }
    val id = c.collection.id
    when (step) {
        CollectionStep.Menu -> Sheet(onDismiss, c.name) {
            MenuRow(Sym.PushPin, if (pinned) "Unpin" else "Pin to the top", {
                CrashLog.note(if (pinned) "unpin collection" else "pin collection")
                AlbumPrefs.setPinned(collectionKey(id), !pinned)
                onDismiss()
            }, trailing = null)
            MenuRow(Sym.Edit, "Rename", { step = CollectionStep.Rename })
            MenuRow(Sym.Image, "Change cover", onCover)
            GroupMoveRows(group, onGroup, onTakeOut)
            if (c.items.isNotEmpty()) ShareZipRow(onShare)
            MenuRow(Sym.Delete, "Delete collection", { step = CollectionStep.Delete }, sub = "Its photos stay on your phone", trailing = null)
        }
        CollectionStep.Rename -> CollectionNameSheet("Rename collection", c.name, "Rename", all.filter { it !== c }.map { it.name }, onDismiss) { name ->
            onDismiss()
            CrashLog.note("rename collection")
            Collections.rename(id, name)
        }
        CollectionStep.Delete -> ConfirmBar(
            title = "Delete ${c.name}?",
            body = "Only the collection goes. Its ${if (c.items.size == 1) "1 item stays" else "${itemCount(c.items.size)} stay"} on your phone.",
            confirmLabel = "Delete collection",
            onConfirm = {
                onDismiss()
                CrashLog.note("delete collection")
                AlbumPrefs.setPinned(collectionKey(id), false) // ids are never reused, but a stale pin is still clutter
                Collections.delete(id)
            },
            onCancel = onDismiss,
            thumbs = c.items.take(2),
        )
    }
}

private enum class CollectionStep { Menu, Rename, Delete }

/** Picks where a selection goes: an existing collection, or a new one named here. [onPick] gets null as the id for a new one. */
@Composable
fun AddToCollectionSheet(count: Int, collections: List<CollectionAlbum>, onPick: (id: Long?, name: String) -> Unit, onDismiss: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    if (naming) {
        CollectionNameSheet("New collection", "", "Create", collections.map { it.name }, onDismiss) { onPick(null, it) }
        return
    }
    val organisation = Collections.organisation.collectAsState().value
    val chosen = remember(organisation) { organisation?.covers.orEmpty().associate { it.albumKey to it.mediaId } }
    val groupOf = remember(organisation) { organisation?.groupNames().orEmpty() }
    Sheet(onDismiss, "Add ${itemCount(count)} to") {
        LazyColumn {
            item(key = "new") { MenuRow(Sym.LibraryAdd, "New collection", { naming = true }, sub = "Photos stay where they are, without copies", trailing = null) }
            items(collections, key = { it.albumId }) { c ->
                CoverRow(pickerName(c.name, groupOf[collectionKey(c.collection.id)]), itemCount(c.items.size), { onPick(c.collection.id, c.name) }) {
                    val cover = pickCover(chosen[collectionKey(c.collection.id)], { id -> c.items.find { it.id == id } }, { true }, c.items.firstOrNull())
                    if (cover != null) Thumbnail(cover, Modifier.matchParentSize()) else Icon(Sym.CollectionsBookmark, null, tint = AlbumTheme.colors.inkMuted)
                }
            }
        }
    }
}

/** Asks for a collection name. [confirm] stays off until [collectionNameError] is happy; [taken] are the other collections' names. */
@Composable
private fun CollectionNameSheet(
    title: String,
    initial: String,
    confirm: String,
    taken: List<String>,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    // Starts with the old name selected, so typing replaces it
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val error = collectionNameError(text.text, taken)
    val showError = error != null && text.text.isNotEmpty() // no "Enter a name" before anything is typed
    NameSheet(
        title, text, { text = it }, confirm, enabled = error == null, onDismiss = onDismiss, onConfirm = { if (error == null) onDone(text.text.trim()) },
        note = error.takeIf { showError }, isError = showError,
    )
}
