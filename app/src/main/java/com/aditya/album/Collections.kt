package com.aditya.album

import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.Room
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Collections open through the same album id (a Long) as folders and [Special] albums, so navigation, saved state and
 * AlbumsGrid.onOpen work unchanged. Their ids start at 2^48: folder ids are BUCKET_IDs (32-bit hashes, so the Int range)
 * and Special ids are small multiples of 2^32, so neither can reach this range.
 */
private const val COLLECTION_IDS = 1L shl 48

fun albumIdOf(collectionId: Long) = COLLECTION_IDS + collectionId

/** The collection behind an album id, or null for a folder or a [Special] album. */
fun collectionIdOf(albumId: Long): Long? = if (albumId >= COLLECTION_IDS) albumId - COLLECTION_IDS else null

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

const val EMPTY_COLLECTION = "Nothing here yet. Long-press photos in Photos or any album, then tap Add to collection."

/** A collection with its items that are still on the phone, newest first. */
data class CollectionAlbum(val collection: MediaCollection, val items: List<Media>) {
    val albumId get() = albumIdOf(collection.id)
    val name get() = collection.name
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

/** The Collections section of the Albums tab: a New collection tile, then each collection. Long-press one to rename or delete it. */
fun LazyGridScope.collectionsSection(collections: List<CollectionAlbum>, onOpen: (Long) -> Unit) {
    item(key = "collections", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Collections") }
    item(key = "new collection") { NewCollectionTile(collections.map { it.name }, onOpen) }
    items(collections, key = { it.albumId }) { CollectionTile(it, collections, onOpen) }
}

@Composable
private fun NewCollectionTile(taken: List<String>, onOpen: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var naming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.clickable { naming = true }) {
        Cover(null, Icons.Outlined.Add, Modifier.fillMaxWidth().aspectRatio(1f))
        Text("New collection", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (naming) {
        CollectionNameDialog("New collection", "", "Create", taken, onDismiss = { naming = false }) { name ->
            naming = false
            CrashLog.note("new collection")
            scope.launch { safely { Collections.create(name) }?.let { onOpen(albumIdOf(it)) } } // opens it, with a hint on how to add photos
        }
    }
}

@Composable
private fun CollectionTile(c: CollectionAlbum, all: List<CollectionAlbum>, onOpen: (Long) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Box {
        Column(
            Modifier.combinedClickable(
                onLongClickLabel = "Rename or delete",
                onLongClick = { menu = true }, // combinedClickable buzzes on long-press by itself
            ) { onOpen(c.albumId) },
        ) {
            Cover(c.items.firstOrNull(), Icons.Outlined.CollectionsBookmark, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(c.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.items.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                menu = false
                renaming = true
            })
            DropdownMenuItem(text = { Text("Delete collection") }, onClick = {
                menu = false
                deleting = true
            })
        }
    }
    if (renaming) {
        CollectionNameDialog("Rename collection", c.name, "Rename", all.filter { it !== c }.map { it.name }, onDismiss = { renaming = false }) { name ->
            renaming = false
            CrashLog.note("rename collection")
            Collections.rename(c.collection.id, name)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Only the collection is deleted. Its photos and videos stay on your phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        CrashLog.note("delete collection")
                        Collections.delete(c.collection.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** Picks where a selection goes: an existing collection, or a new one named here. [onPick] gets null as the id for a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionSheet(count: Int, collections: List<CollectionAlbum>, onPick: (id: Long?, name: String) -> Unit, onDismiss: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Add ${itemCount(count)} to", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "new") { SheetRow("New collection", null, null, Icons.Outlined.Add) { naming = true } }
            items(collections, key = { it.albumId }) { c ->
                SheetRow(c.name, "${c.items.size}", c.items.firstOrNull(), Icons.Outlined.CollectionsBookmark) { onPick(c.collection.id, c.name) }
            }
        }
    }
    if (naming) CollectionNameDialog("New collection", "", "Create", collections.map { it.name }, onDismiss = { naming = false }) { onPick(null, it) }
}

@Composable
private fun SheetRow(name: String, count: String?, cover: Media?, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, icon, Modifier.size(56.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            count?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A collection's cover photo, or [icon] when it has none (empty, or the New collection tile). */
@Composable
private fun Cover(media: Media?, icon: ImageVector, modifier: Modifier) {
    val shape = RoundedCornerShape(LocalLook.current.cellCorner + 8.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (media != null) {
            AsyncImage(Thumb(media.uri), contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Asks for a collection name. Create stays off until [collectionNameError] is happy; [taken] are the other collections' names. */
@Composable
private fun CollectionNameDialog(
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
    val done = { if (error == null) onDone(text.text.trim()) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                Modifier.focusRequester(focus),
                label = { Text("Name") },
                // No "Enter a name" before anything is typed
                supportingText = { if (text.text.isNotEmpty()) error?.let { Text(it) } },
                isError = error != null && text.text.isNotEmpty(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
                singleLine = true,
            )
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = done, enabled = error == null) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
