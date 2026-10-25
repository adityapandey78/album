package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

// Folder albums: move and copy photos between them, create, rename, pin, hide from Photos, delete, and sort.
// Files only ever change through MediaStore, one item at a time, so a job that fails or stops part-way leaves
// every file whole: each one is either moved (or copied) or still exactly where it was.

enum class Transfer(val verb: String, val doing: String, val done: String) {
    Move("move", "Moving", "Moved"),
    Copy("copy", "Copying", "Copied"),
}

enum class AlbumSort(val label: String) {
    Default("Default"),
    Name("Name"),
    Count("Most items"),
    Updated("Recently updated"),
}

/** What the long-press menu on a folder album can do. */
enum class AlbumAction { Rename, Pin, Hide, Delete }

/**
 * A folder's identity for pins and hiding: its RELATIVE_PATH, lower-cased because shared storage ignores case.
 * Unlike BUCKET_ID it's known before a rename happens, so a renamed album keeps its pin.
 */
fun folderKey(relativePath: String) = relativePath.lowercase()

val Album.key get() = folderKey(cover.path)

/** These items minus the folders hidden from Photos. Every timeline-style view (Photos, calendar, search) should use it. */
fun List<Media>.inPhotos(hidden: Set<String>) = if (hidden.isEmpty()) this else filter { folderKey(it.path) !in hidden }

/**
 * Albums in display order: pinned first, then the rest, each part in [sort] order. [albums] must be in the Default
 * order ([albumsOf]'s). Generic so it's testable without MediaStore rows.
 */
fun <T> orderAlbums(
    albums: List<T>,
    sort: AlbumSort,
    isPinned: (T) -> Boolean,
    name: (T) -> String,
    count: (T) -> Int,
    newest: (T) -> Long,
): List<T> {
    val sorted = when (sort) {
        AlbumSort.Default -> albums
        AlbumSort.Name -> albums.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, name))
        AlbumSort.Count -> albums.sortedByDescending(count)
        // ponytail: newest photo's date, so a moved-in old photo doesn't count; add DATE_ADDED to Media if that matters
        AlbumSort.Updated -> albums.sortedByDescending(newest)
    }
    return sorted.sortedByDescending(isPinned) // stable, so both parts keep the sort order
}

fun List<Album>.ordered(sort: AlbumSort, pinned: Set<String>) =
    orderAlbums(this, sort, { it.key in pinned }, Album::name, Album::count) { it.cover.taken }

/**
 * Names for files arriving in a folder that already holds [taken]: a clash gets " (1)", " (2)"… before the
 * extension, like Android's own Files app. Case-insensitive, as shared storage is, and arrivals never clash
 * with each other either.
 */
fun freeNames(names: List<String>, taken: Collection<String>): List<String> {
    val used = taken.mapTo(HashSet()) { it.lowercase() }
    return names.map { name ->
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var free = name
        var n = 1
        while (!used.add(free.lowercase())) free = "${name.take(dot)} (${n++})${name.drop(dot)}"
        free
    }
}

/** Why [name] can't be an album name, or null if it can. [existing] holds every album name on the phone. */
fun albumNameError(name: String, existing: Collection<String>): String? {
    val n = name.trim()
    return when {
        n.isEmpty() -> "Enter a name"
        // MediaStore would quietly swap these for "_", so the album would get a different name than the one typed
        n.any { it in "/\\:*?\"<>|" } -> "Names can't contain / \\ : * ? \" < > |"
        n.startsWith('.') -> "Names can't start with a dot" // dot folders are hidden from every gallery
        existing.any { it.equals(n, ignoreCase = true) } -> "There's already an album called $n"
        else -> null
    }
}

/** Whether MediaStore lets a photo (or video) live in the folder at [path]: photos only under DCIM and Pictures, videos also under Movies. */
fun canHold(path: String, isVideo: Boolean): Boolean {
    val top = path.substringBefore('/')
    return top.equals("DCIM", true) || top.equals("Pictures", true) || (isVideo && top.equals("Movies", true))
}

/** RELATIVE_PATH of the folder at [path] after renaming it to [name]: same parent folder. */
fun renamedPath(path: String, name: String) = path.trimEnd('/').substringBeforeLast('/') + "/$name/"

private val systemFolders = setOf("dcim/camera/", "dcim/screenshots/", "pictures/screenshots/", "movies/screen recordings/", "dcim/screen recordings/")

/** Why the folder at [path] can't be renamed, or null if it can. The camera, screenshots and other apps expect their folders to stay put. */
fun renameBlocked(path: String): String? {
    val folder = path.trimEnd('/').substringAfterLast('/')
    return when {
        folderKey(path) in systemFolders || path.trimEnd('/').count { it == '/' } == 0 ->
            "$folder is a system folder. Your camera and other apps expect it to keep its name."
        !canHold(path, isVideo = true) -> "Android only lets Album rename folders in DCIM, Pictures and Movies"
        else -> null
    }
}

/** Snackbar text after moving or copying into [album], or null when nothing was tried (the user said no). */
fun transferMessage(transfer: Transfer, done: Int, failed: Int, album: String): String? = when {
    done == 0 && failed == 0 -> null
    done == 0 -> "Couldn't ${transfer.verb} ${itemCount(failed)} to $album"
    failed == 0 -> "${transfer.done} ${itemCount(done)} to $album"
    else -> "${transfer.done} ${itemCount(done)} to $album. $failed couldn't be ${transfer.done.lowercase()}."
}

/** Album choices that live outside MediaStore, in the "settings" prefs: pinned folders, folders hidden from Photos, and the sort. */
object AlbumPrefs {
    private const val PINNED = "albums_pinned"
    private const val HIDDEN = "albums_hidden"
    private const val SORT = "albums_sort"
    private lateinit var prefs: SharedPreferences
    private val pinnedKeys = MutableStateFlow(emptySet<String>())
    private val hiddenKeys = MutableStateFlow(emptySet<String>())
    private val sortOrder = MutableStateFlow(AlbumSort.Default)

    /** [folderKey]s of pinned albums, which come first. */
    val pinned = pinnedKeys.asStateFlow()

    /** [folderKey]s of albums left out of Photos (see [inPhotos]). They still show in Albums. */
    val hidden = hiddenKeys.asStateFlow()
    val sort = sortOrder.asStateFlow()

    /** Call before the first screen draws, so Photos never flashes a hidden folder. Safe to call repeatedly. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        pinnedKeys.value = prefs.getStringSet(PINNED, null).orEmpty().toSet()
        hiddenKeys.value = prefs.getStringSet(HIDDEN, null).orEmpty().toSet()
        sortOrder.value = AlbumSort.entries.find { it.name == prefs.getString(SORT, null) } ?: AlbumSort.Default
    }

    fun setPinned(key: String, on: Boolean) = save(pinnedKeys, PINNED, key, on)

    fun setHidden(key: String, on: Boolean) = save(hiddenKeys, HIDDEN, key, on)

    fun setSort(sort: AlbumSort) {
        sortOrder.value = sort
        prefs.edit { putString(SORT, sort.name) }
    }

    private fun save(keys: MutableStateFlow<Set<String>>, pref: String, key: String, on: Boolean) {
        val now = keys.updateAndGet { if (on) it + key else it - key }
        prefs.edit { putStringSet(pref, now) }
    }
}

/**
 * Moves, copies and renames. They run in the app's scope rather than the screen's, so turning the phone mid-job
 * neither stops it nor loses its result. One job at a time: the progress dialog blocks the screen while one works.
 */
object AlbumOps {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopping = false

    /** [current] is 1-based: "Moving 3 of 40". */
    class Progress(val doing: String, val current: Int, val total: Int)

    /** A MediaStore write request waiting for Android's pop-up, which [AlbumActionsHost] shows. */
    class Ask(val request: PendingIntent) {
        internal val answer = CompletableDeferred<Boolean>()
        internal var shown = false
    }

    val progress = MutableStateFlow<Progress?>(null)
    val asking = MutableStateFlow<Ask?>(null)

    /** Results for the snackbar, kept until shown, so a rotation can't drop one. */
    val messages = Channel<String>(Channel.UNLIMITED)

    /** Finishes the item in hand, then stops. Everything done so far stays done. */
    fun stop() {
        stopping = true
        asking.value?.answer?.complete(false) // a pop-up nobody answers would otherwise keep the job waiting
    }

    fun answer(granted: Boolean) {
        asking.value?.answer?.complete(granted)
    }

    /** Moves or copies [items] into the folder at [path], shown to the user as [album]. */
    fun transfer(context: Context, items: List<Media>, path: String, album: String, transfer: Transfer) = start {
        val todo = if (transfer == Transfer.Move) items.filter { folderKey(it.path) != folderKey(path) } else items
        when {
            todo.isEmpty() -> "Already in $album"
            transfer == Transfer.Move -> move(context.contentResolver, todo, path, transfer.doing).let { (moved, tried) ->
                transferMessage(transfer, moved, tried - moved, album)
            }
            // Shared storage sits on the same partition as the app's own files
            todo.sumOf { it.size } > context.filesDir.usableSpace -> "Not enough space to copy ${itemCount(todo.size)}"
            else -> {
                val names = freeNames(todo.map { it.name }, namesIn(path))
                var copied = 0
                var tried = 0
                for ((m, name) in todo.zip(names)) {
                    if (stopping) break
                    progress.value = Progress(transfer.doing, ++tried, todo.size)
                    if (copy(context, m, path, name)) copied++
                }
                transferMessage(transfer, copied, tried - copied, album)
            }
        }
    }

    /** Renames a folder by moving every item in it ([items]) to a sibling folder called [name]. */
    fun rename(context: Context, album: Album, items: List<Media>, name: String) = start {
        val path = renamedPath(album.cover.path, name)
        // Carried over before moving, so a hidden album's photos never flash up in Photos on the way
        if (album.key in AlbumPrefs.pinned.value) AlbumPrefs.setPinned(folderKey(path), true)
        if (album.key in AlbumPrefs.hidden.value) AlbumPrefs.setHidden(folderKey(path), true)
        val (moved, tried) = move(context.contentResolver, items, path, "Renaming")
        // Whichever folder ended up empty drops its pin and hiding, so a later folder of that name doesn't inherit them
        val emptied = when (moved) {
            items.size -> album.key
            0 -> folderKey(path)
            else -> null
        }
        emptied?.let {
            AlbumPrefs.setPinned(it, false)
            AlbumPrefs.setHidden(it, false)
        }
        when {
            tried == 0 -> null
            moved == items.size -> "Renamed to $name"
            else -> "Moved $moved of ${itemCount(items.size)} to $name"
        }
    }

    private fun start(work: suspend () -> String?) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin() // only ever one left waiting on a pop-up that a closed screen never answered
            stopping = false
            try {
                work()?.let { messages.trySend(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Each item is moved or copied whole or not at all, so an unexpected failure loses nothing
                CrashLog.note("album job failed: $e")
                messages.trySend("Something went wrong. Nothing was lost.")
            } finally {
                progress.value = null
                asking.value = null
            }
        }
    }

    private suspend fun ask(request: PendingIntent): Boolean {
        val ask = Ask(request)
        asking.value = ask
        return ask.answer.await().also { asking.value = null }
    }

    /**
     * Moves [items] into [path] by updating RELATIVE_PATH: Android renames the file in place, so nothing is copied
     * or deleted, and its favourite, dates and smart index entry stay. Asks for write access in requests of at most
     * [MAX_REQUEST]. Returns how many moved and how many were tried.
     */
    private suspend fun move(resolver: ContentResolver, items: List<Media>, path: String, doing: String): Pair<Int, Int> {
        val names = freeNames(items.map { it.name }, namesIn(path))
        // Ask for every batch before moving anything: a "Deny" on the 2nd pop-up then can't leave an album split across
        // two folders. Android keeps each grant for as long as Album runs.
        for (batch in items.chunked(MAX_REQUEST)) {
            if (stopping || !ask(MediaStore.createWriteRequest(resolver, batch.map { it.uri }))) return 0 to 0
        }
        var moved = 0
        var tried = 0
        for (i in items.indices) {
            if (stopping) break
            progress.value = Progress(doing, ++tried, items.size)
            val values = ContentValues().apply {
                put(MediaColumns.RELATIVE_PATH, path)
                if (names[i] != items[i].name) put(MediaColumns.DISPLAY_NAME, names[i])
            }
            runCatching { resolver.update(items[i].uri, values, null, null) }
                .onSuccess { if (it == 1) moved++ }
                .onFailure { CrashLog.note("move failed: $it") } // e.g. the file vanished meanwhile
        }
        return moved to tried
    }

    /**
     * Copies [m] to a new MediaStore row in [path]. The row stays pending (invisible to other apps) until every
     * byte is written; if anything fails it's deleted again. The original is only ever read.
     */
    private fun copy(context: Context, m: Media, path: String, name: String): Boolean {
        val resolver = context.contentResolver
        // Without this Android blanks the GPS tags in what it reads out, and the copy would lose where it was taken
        val source = if (context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) MediaStore.setRequireOriginal(m.uri) else m.uri
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, name)
            put(MediaColumns.RELATIVE_PATH, path)
            put(MediaColumns.MIME_TYPE, resolver.getType(m.uri))
            put(MediaColumns.DATE_TAKEN, m.taken) // keeps the copy next to the original in the timeline
            put(MediaColumns.IS_PENDING, 1)
        }
        val collection = if (m.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        var copy: Uri? = null
        return try {
            val uri = checkNotNull(resolver.insert(collection, values)).also { copy = it }
            checkNotNull(resolver.openInputStream(source)).use { input ->
                // 1 MB chunks: the default 8 KB means hundreds of thousands of trips through Android's storage layer for one video
                checkNotNull(resolver.openOutputStream(uri, "w")).use { input.copyTo(it, 1 shl 20) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            // Publishing rescans the file and recomputes DATE_TAKEN: from the copy's own "now" timestamp when there's no
            // EXIF date (screenshots, WhatsApp images), or with a guessed timezone. Put the original's back so the copy
            // stays next to it in the timeline. Best effort: the copy itself is already complete.
            runCatching { resolver.update(uri, ContentValues().apply { put(MediaColumns.DATE_TAKEN, m.taken) }, null, null) }
            true
        } catch (e: Exception) {
            CrashLog.note("copy failed: $e")
            copy?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }

    /** File names already in the folder at [path] that Album can see. MediaStore itself picks a free name if a file Album can't see is in the way. */
    private fun namesIn(path: String) = Gallery.media.value.orEmpty().filter { folderKey(it.path) == folderKey(path) }.map { it.name }
}

/** What the album actions are showing. Held by the main screen and drawn by [AlbumActionsHost]. */
@Stable
class AlbumActions internal constructor(private val context: Context, private val onStart: () -> Unit) {
    internal sealed interface Step {
        class Pick(val items: List<Media>, val transfer: Transfer) : Step
        /** Naming a new album: for [pick]'s items, or (when null) before choosing its photos. */
        class NewName(val pick: Pick?) : Step
        class Choose(val name: String) : Step
        class Rename(val album: Album) : Step
        class Delete(val album: Album) : Step
    }

    internal var step by mutableStateOf<Step?>(null)

    /** Selection bar: pick an album (or make one), then move or copy [items] into it. */
    fun moveOrCopy(items: List<Media>, transfer: Transfer) {
        if (context.canSend(items.size, MAX_REQUEST, transfer.verb)) step = Step.Pick(items, transfer)
    }

    /** The New album tile: name it, choose its photos, then copy or move them in. */
    fun newAlbum() {
        CrashLog.note("new album")
        step = Step.NewName(null)
    }

    fun onAlbum(album: Album, action: AlbumAction) {
        CrashLog.note("album ${action.name}")
        when (action) {
            AlbumAction.Rename -> {
                val blocked = renameBlocked(album.cover.path)
                if (blocked != null) AlbumOps.messages.trySend(blocked) else step = Step.Rename(album)
            }
            AlbumAction.Pin -> AlbumPrefs.setPinned(album.key, album.key !in AlbumPrefs.pinned.value)
            AlbumAction.Hide -> AlbumPrefs.setHidden(album.key, album.key !in AlbumPrefs.hidden.value)
            AlbumAction.Delete -> step = Step.Delete(album)
        }
    }

    internal fun start(items: List<Media>, path: String, album: String, transfer: Transfer) {
        step = null
        onStart()
        CrashLog.note("${transfer.verb} ${items.size}")
        AlbumOps.transfer(context.applicationContext, items, path, album, transfer)
    }
}

/** [onStart] runs when a move or copy begins (the selection it came from is done). */
@Composable
fun rememberAlbumActions(onStart: () -> Unit): AlbumActions {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onStart)
    return remember(context) { AlbumActions(context) { latest() } }
}

/**
 * Draws what [actions] asks for (album picker, name and delete dialogs, choosing a new album's photos), Android's
 * write pop-ups, job progress, and results in [snackbar]. [media] is every photo and video, [albums] every folder.
 */
@Composable
fun AlbumActionsHost(actions: AlbumActions, media: List<Media>, albums: List<Album>, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val trashWithUndo = rememberTrashWithUndo(snackbar)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        AlbumOps.answer(it.resultCode == Activity.RESULT_OK)
    }
    val ask = AlbumOps.asking.collectAsState().value
    LaunchedEffect(ask) {
        if (ask != null && !ask.shown) {
            ask.shown = true // a screen recreated while Android's pop-up is open must not show it twice
            request.launch(IntentSenderRequest.Builder(ask.request).build())
        }
    }
    LaunchedEffect(snackbar) { for (message in AlbumOps.messages) snackbar.showSnackbar(message) }

    val names = remember(albums) { albums.map { it.name } }
    val close = { actions.step = null }
    when (val step = actions.step) {
        null -> {}
        is AlbumActions.Step.Pick -> {
            val pinned by AlbumPrefs.pinned.collectAsState()
            val sort by AlbumPrefs.sort.collectAsState()
            // Folders MediaStore allows these items in, minus the one they're all in already
            val targets = remember(albums, step, pinned, sort) {
                albums.ordered(sort, pinned).filter { a -> step.items.all { canHold(a.cover.path, it.isVideo) } && step.items.any { it.bucketId != a.id } }
            }
            AlbumPicker(step.transfer, targets, close, onNew = { actions.step = AlbumActions.Step.NewName(step) }) { a ->
                actions.start(step.items, a.cover.path, a.name, step.transfer)
            }
        }
        is AlbumActions.Step.NewName -> NameDialog("New album", "", step.pick?.transfer?.name ?: "Next", names, close) { name ->
            val pick = step.pick
            if (pick == null) actions.step = AlbumActions.Step.Choose(name) else actions.start(pick.items, "Pictures/$name/", name, pick.transfer)
        }
        is AlbumActions.Step.Choose -> ChoosePhotos(step.name, media, close) { items, transfer ->
            actions.start(items, "Pictures/${step.name}/", step.name, transfer)
        }
        is AlbumActions.Step.Rename -> NameDialog("Rename album", step.album.name, "Rename", names, close) { name ->
            close()
            AlbumOps.rename(context.applicationContext, step.album, media.filter { it.bucketId == step.album.id }, name)
        }
        is AlbumActions.Step.Delete -> {
            val items = remember(media, step) { media.filter { it.bucketId == step.album.id } }
            val tooMany = items.size > MAX_REQUEST
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Delete ${step.album.name}?") },
                text = {
                    Text(
                        if (tooMany) {
                            "It has ${itemCount(items.size)}, and Android moves up to ${"%,d".format(MAX_REQUEST)} at a time to Recently deleted. " +
                                "Open the album and delete its photos in parts."
                        } else {
                            "${itemCount(items.size)} will move to Recently deleted. You can restore them there for 30 days."
                        },
                    )
                },
                confirmButton = {
                    if (tooMany) {
                        TextButton(onClick = close) { Text("OK") }
                    } else {
                        TextButton(
                            onClick = {
                                close()
                                CrashLog.note("delete album ${items.size}")
                                // a future folder at the same path must not silently inherit this one's pin or hiding
                                AlbumPrefs.setPinned(step.album.key, false)
                                AlbumPrefs.setHidden(step.album.key, false)
                                trashWithUndo(items)
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                    }
                },
                dismissButton = { if (!tooMany) TextButton(onClick = close) { Text("Cancel") } },
            )
        }
    }

    AlbumOps.progress.collectAsState().value?.let { p ->
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(400) // quick jobs finish without a dialog flashing up
            visible = true
        }
        if (visible) {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("${p.doing} ${p.current} of ${p.total}") },
                text = { LinearProgressIndicator(progress = { (p.current - 1f) / p.total }, Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = AlbumOps::stop) { Text("Stop") } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumPicker(transfer: Transfer, albums: List<Album>, onDismiss: () -> Unit, onNew: () -> Unit, onPick: (Album) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("${transfer.name} to album", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                PickerRow("New album", null, onNew) { Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary) }
            }
            items(albums, key = { it.id }) { a ->
                PickerRow(a.name, itemCount(a.count), { onPick(a) }) {
                    AsyncImage(Thumb(a.cover.uri), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, onClick: () -> Unit, picture: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(LocalLook.current.cellCorner + 4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { picture() }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Asks for an album name. Errors show once the text has changed, and [confirm] only works for a valid name. */
@Composable
private fun NameDialog(title: String, initial: String, confirm: String, taken: Collection<String>, onDismiss: () -> Unit, onName: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) } // selected, so typing replaces it
    val error = albumNameError(text.text, taken)
    val done = { if (error == null) onName(text.text.trim()) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val showError = error != null && text.text != initial
            OutlinedTextField(
                text,
                { text = it },
                Modifier.focusRequester(focus),
                label = { Text("Name") },
                supportingText = { if (showError) Text(error.orEmpty()) },
                isError = showError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = done, enabled = error == null) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen photo chooser for a new album called [name]: select with taps or long-press and drag, then copy or move. */
@Composable
private fun ChoosePhotos(name: String, media: List<Media>, onBack: () -> Unit, onDone: (List<Media>, Transfer) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val columns = remember { mutableIntStateOf(DEFAULT_COLUMNS) }
    val grid = rememberLazyGridState()
    val done = { transfer: Transfer ->
        val items = media.filter { it.id in selected }
        if (context.canSend(items.size, MAX_REQUEST, transfer.verb)) onDone(items, transfer)
    }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {} // catches taps on empty space, which would otherwise reach the albums screen underneath
            .systemBarsPadding(),
    ) {
        TitleBar(if (selected.isEmpty()) "Add to $name" else "${selected.size} selected", backIcon = Icons.Outlined.Close, onBack = onBack)
        // Not selecting yet, a tap selects instead of opening the viewer
        MediaGrid(media, columns, grid, selected, { selected = selected + it.id }, { selected = it }, PaddingValues(), Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { done(Transfer.Copy) }, Modifier.weight(1f), enabled = selected.isNotEmpty()) { Text("Copy here") }
            Button(onClick = { done(Transfer.Move) }, Modifier.weight(1f), enabled = selected.isNotEmpty()) { Text("Move here") }
        }
    }
}

/** Long-press menu of a folder album. */
@Composable
fun AlbumMenu(expanded: Boolean, pinned: Boolean, hidden: Boolean, onDismiss: () -> Unit, onAction: (AlbumAction) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("Rename") }, { onAction(AlbumAction.Rename) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
        DropdownMenuItem({ Text(if (pinned) "Unpin" else "Pin") }, { onAction(AlbumAction.Pin) }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) })
        DropdownMenuItem(
            { Text(if (hidden) "Show in Photos" else "Hide from Photos") },
            { onAction(AlbumAction.Hide) },
            leadingIcon = { Icon(if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) },
        )
        DropdownMenuItem({ Text("Delete") }, { onAction(AlbumAction.Delete) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
    }
}

/** Last tile of the Albums grid. */
@Composable
fun NewAlbumTile(onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(LocalLook.current.cellCorner + 8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) }
        Text("New album", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall)
    }
}

/** Albums top bar: how folders are ordered. Pinned ones always come first. */
@Composable
fun AlbumSortButton() {
    var open by remember { mutableStateOf(false) }
    val sort by AlbumPrefs.sort.collectAsState()
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort albums") }
        DropdownMenu(open, { open = false }) {
            AlbumSort.entries.forEach { s ->
                DropdownMenuItem(
                    { Text(s.label) },
                    {
                        CrashLog.note("sort albums ${s.name}")
                        AlbumPrefs.setSort(s)
                        open = false
                    },
                    trailingIcon = { if (s == sort) Icon(Icons.Outlined.Check, "Selected") },
                )
            }
        }
    }
}
