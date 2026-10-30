package com.aditya.album

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * A photo or video in Android's trash. Android deletes it for good at [expires] (epoch millis). [taken] (epoch millis)
 * and [day] group it by the day it was taken, as the Library does; [width] and [height] are as shown.
 */
data class Trashed(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val expires: Long,
    val durationMs: Long = 0,
    val taken: Long = 0,
    val day: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

// What a tile, a thumbnail and the grid's day sections read; the rest stays empty
private fun Trashed.toMedia() = Media(id, uri, isVideo, taken, day, width, height, durationMs, 0, "", false, 0, "", "")

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Days left before Android deletes a trashed item for good. Rounded up, so a fresh item shows 30 and never 0. */
fun daysLeft(expires: Long, now: Long): Int = ((expires - now + DAY_MS - 1) / DAY_MS).coerceAtLeast(1).toInt()

/** "1 album", "2,000 albums": a count in the phone's grouping with the right word. */
fun counted(n: Int, one: String, many: String) = "${"%,d".format(n)} ${if (n == 1) one else many}"

fun itemCount(n: Int) = counted(n, "item", "items")

/** How a confirmation names what it acts on: "this photo", "this video" or "12 items". */
fun these(n: Int, oneIsVideo: Boolean) = if (n == 1) (if (oneIsVideo) "this video" else "this photo") else itemCount(n)

fun restoreRequest(resolver: ContentResolver, uris: List<Uri>) = MediaStore.createTrashRequest(resolver, uris, false)

/** Everything in Android's trash, newest taken first, reloaded whenever MediaStore changes. null until the first load. */
@Composable
fun rememberTrash(): State<List<Trashed>?> {
    val resolver = LocalContext.current.contentResolver
    return produceState<List<Trashed>?>(null, resolver) {
        Gallery.changes(resolver)
            .conflate()
            .map { loadTrash(resolver) }
            .flowOn(Dispatchers.IO)
            .catch { CrashLog.note("trash load failed: $it"); emit(emptyList()) }
            .collect { value = it }
    }
}

private fun loadTrash(resolver: ContentResolver): List<Trashed> {
    val args = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, PHOTOS_AND_VIDEOS)
    }
    val projection = arrayOf(
        FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_EXPIRES, FileColumns.DURATION, FileColumns.DATE_TAKEN,
        FileColumns.DATE_MODIFIED, FileColumns.WIDTH, FileColumns.HEIGHT, FileColumns.ORIENTATION,
    )
    val zone = ZoneId.systemDefault()
    val out = ArrayList<Trashed>()
    resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val isVideo = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            // Android renames a trashed file, so its name can't stand in for a missing date: the file's own date does
            val taken = takenOf(c.getLong(4), c.getLong(5), "", zone)
            val sideways = c.getInt(8) % 180 == 90
            // DATE_EXPIRES is in seconds; DURATION is null for photos, which reads as 0
            out += Trashed(
                id, mediaUri(id, isVideo), isVideo, expires = c.getLong(2) * 1000, durationMs = c.getLong(3),
                taken = taken, day = Instant.ofEpochMilli(taken).atZone(zone).toLocalDate().toEpochDay(),
                width = c.getInt(if (sideways) 7 else 6), height = c.getInt(if (sideways) 6 else 7),
            )
        }
    }
    out.sortByDescending { it.taken }
    return out
}

/**
 * Returns a function that moves items to trash, then offers Undo in [snackbar].
 * Grid selection uses it today; the viewer can share it (same [snackbar]) so every delete gets the same Undo.
 */
@Composable
fun rememberTrashWithUndo(snackbar: SnackbarHostState, onTrashed: () -> Unit = {}): (Collection<Media>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Strings so they survive the activity being recreated while Android's pop-up is open
    var pending by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        onTrashed()
        val uris = pending.map(Uri::parse)
        scope.launch {
            val undo = snackbar.showSnackbar("Moved ${itemCount(uris.size)} to Trash", "Undo", duration = SnackbarDuration.Long)
            if (undo == SnackbarResult.ActionPerformed) {
                CrashLog.note("undo trash ${uris.size}")
                restore.launch(IntentSenderRequest.Builder(restoreRequest(context.contentResolver, uris)).build())
            }
        }
    }
    return trash@{ items ->
        if (!context.canSend(items.size, MAX_REQUEST, "delete")) return@trash
        pending = items.map { it.uri.toString() }
        trash.launch(IntentSenderRequest.Builder(trashRequest(context.contentResolver, items)).build())
    }
}

/**
 * Recently deleted, pushed over the tabs: the Library's grid (its [columns], gallery style and pinch), by the day each was
 * taken, with the days left on each tile. Long-press to select, then restore or delete forever from the SelectionBar;
 * Empty trash sits at the top right. Deleting forever is held to confirm, since nothing brings it back.
 */
@Composable
fun TrashScreen(items: List<Trashed>?, columns: MutableIntState, onBack: () -> Unit) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val c = spec.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var toDelete by remember { mutableStateOf<List<Trashed>?>(null) } // waiting for Album's own hold-to-confirm
    var done by rememberSaveable { mutableStateOf<String?>(null) } // what to say once Android says yes
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            selected = emptySet()
            done?.let { message -> scope.launch { snackbar.showSnackbar(message) } }
        }
        done = null
    }
    val trash = items.orEmpty()
    val media = remember(items) { trash.map { it.toMedia() } }
    val byId = remember(items) { trash.associateBy { it.id } }
    val grid = rememberLazyGridState()
    val chosen = trash.filter { it.id in selected } // ignores ids that left the trash meanwhile
    val selecting = chosen.isNotEmpty()
    val now = remember(items) { System.currentTimeMillis() }
    val deleteNow = { list: List<Trashed> ->
        CrashLog.note("delete forever ${list.size}")
        done = "Deleted ${itemCount(list.size)} forever"
        request.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, list.map { it.uri })).build())
    }
    // With Media management access Android deletes without a pop-up, so Album asks; without it Android asks, and once is enough
    val forever = { list: List<Trashed> ->
        if (context.canSend(list.size, MAX_REQUEST, "delete")) {
            if (MediaStore.canManageMedia(context)) toDelete = list else deleteNow(list)
        }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler { if (selecting) selected = emptySet() else onBack() }
    Box(Modifier.fillMaxSize().background(c.canvas).pointerInput(Unit) {}) { // catches taps that would reach the screen underneath
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(c.canvas).statusBarsPadding()) {
                CollapsedTopBar(Special.Trash.title, subtitle = "Erased 30 days after you delete them", onBack = onBack) {
                    if (trash.isNotEmpty()) AlbumIconButton(Sym.DeleteForever, "Empty trash", { forever(trash) })
                }
            }
            when {
                items == null -> {} // first load takes a few ms
                trash.isEmpty() -> EmptyState(Sym.Delete, "Trash is empty", "Deleted photos stay here for 30 days.")
                else -> MediaGrid(
                    media,
                    columns,
                    grid,
                    selected,
                    onOpen = {
                        // one at a time: each tap would otherwise queue another
                        if (snackbar.currentSnackbarData == null) scope.launch { snackbar.showSnackbar("Restore it first to open it") }
                    },
                    onSelect = { selected = it },
                    contentPadding = PaddingValues(bottom = bottom + if (selecting) SelectionClearance else Space.s8),
                    header = { Note("Long-press to select, then Restore or Delete forever.") },
                    // Days left before Android erases it, on every tile
                    badge = { m -> byId[m.id]?.let { "${daysLeft(it.expires, now)}d" } },
                )
            }
        }
        // The bar rises 28dp with a slight overshoot, and goes at once
        val still = spec.still
        val rise = with(LocalDensity.current) { 28.dp.roundToPx() }
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = if (still) EnterTransition.None else slideInVertically(tween(300, easing = riseEase)) { rise } + fadeIn(tween(300)),
            exit = ExitTransition.None,
        ) {
            SelectionBar(
                chosen.size,
                listOf(
                    SelAction(Sym.RestoreFromTrash, "Restore") {
                        if (context.canSend(chosen.size, MAX_REQUEST, "restore")) {
                            CrashLog.note("restore ${chosen.size}")
                            done = "Restored ${itemCount(chosen.size)}"
                            request.launch(IntentSenderRequest.Builder(restoreRequest(context.contentResolver, chosen.map { it.uri })).build())
                        }
                    },
                    SelAction(Sym.DeleteForever, "Delete forever", danger = true) { forever(chosen) },
                ),
                onClose = { selected = emptySet() },
                onSelectAll = { selected = trash.mapTo(HashSet()) { it.id } },
            )
        }
        AlbumSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (selecting) SelectionBarHeight + Space.s2 else 0.dp),
        )
    }

    toDelete?.let { list ->
        val all = list.size == trash.size
        ConfirmBar(
            title = if (all && list.size > 1) "Empty Trash?" else "Delete ${these(list.size, list.first().isVideo)} forever?",
            body = if (all && list.size > 1) "All ${itemCount(list.size)} will be erased from this phone. This can't be undone."
            else "${if (list.size == 1) "It'll" else "They'll"} be erased from this phone. This can't be undone.",
            confirmLabel = if (all && list.size > 1) "Hold to empty" else "Hold to delete",
            onConfirm = {
                toDelete = null
                deleteNow(list)
            },
            onCancel = { toDelete = null },
            icon = Sym.DeleteForever,
            hold = true,
            thumbs = list.take(2).map { it.toMedia() },
            fromBar = selecting,
        )
    }
}

/** The selection bar's rise: a slight overshoot, as in the design. */
private val riseEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.1f)
