package com.aditya.album

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A photo or video in Android's trash. Android deletes it for good at [expires] (epoch millis). */
data class Trashed(val id: Long, val uri: Uri, val isVideo: Boolean, val expires: Long, val durationMs: Long = 0)

// Just what a tile and a thumbnail read; trashed items have no place in the timeline, so the rest stays empty
private fun Trashed.toMedia() = Media(id, uri, isVideo, 0, 0, 0, 0, durationMs, 0, "", false, 0, "", "")

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Days left before Android deletes a trashed item for good. Rounded up, so a fresh item shows 30 and never 0. */
fun daysLeft(expires: Long, now: Long): Int = ((expires - now + DAY_MS - 1) / DAY_MS).coerceAtLeast(1).toInt()

/** "1 album", "2,000 albums": a count in the phone's grouping with the right word. */
fun counted(n: Int, one: String, many: String) = "${"%,d".format(n)} ${if (n == 1) one else many}"

fun itemCount(n: Int) = counted(n, "item", "items")

fun restoreRequest(resolver: ContentResolver, uris: List<Uri>) = MediaStore.createTrashRequest(resolver, uris, false)

/** Everything in Android's trash, newest deleted first, reloaded whenever MediaStore changes. null until the first load. */
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
        // Android sets the expiry to 30 days after the move to trash, so this is newest deleted first
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${FileColumns.DATE_EXPIRES} DESC")
    }
    val projection = arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_EXPIRES, FileColumns.DURATION)
    val out = ArrayList<Trashed>()
    resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val isVideo = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            // DATE_EXPIRES is in seconds; DURATION is null for photos, which reads as 0
            out += Trashed(id, mediaUri(id, isVideo), isVideo, expires = c.getLong(2) * 1000, durationMs = c.getLong(3))
        }
    }
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

// The SelectionBar's height (count row, gap, action bar), and the room the grid leaves under its last row for it
private val selectionBar = Space.target + Space.s2 + Space.bar
private val selectionClearance = selectionBar + Space.s4 + Space.s8

/**
 * Recently deleted, pushed over the tabs. Long-press to select, then restore or delete forever from the SelectionBar;
 * Empty trash sits at the top right. Deleting forever is held to confirm, since nothing brings it back.
 */
@Composable
fun TrashScreen(items: List<Trashed>?, onBack: () -> Unit) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val c = spec.colors
    val haptics = rememberHaptics()
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
    val tiles = remember(items) { trash.map { it to it.toMedia() } }
    val chosen = trash.filter { it.id in selected } // ignores ids that left the trash meanwhile
    val selecting = chosen.isNotEmpty()
    val now = remember(items) { System.currentTimeMillis() }
    // Album asks itself because with Media management access Android deletes without any pop-up
    val forever = { list: List<Trashed> -> if (context.canSend(list.size, MAX_REQUEST, "delete")) toDelete = list }
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
                else -> LazyVerticalGrid(
                    GridCells.Fixed(DEFAULT_COLUMNS),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = Space.s3, bottom = bottom + if (selecting) selectionClearance else Space.s8),
                    horizontalArrangement = Arrangement.spacedBy(spec.gutter),
                    verticalArrangement = Arrangement.spacedBy(spec.gutter),
                ) {
                    item(key = "note", span = { GridItemSpan(maxLineSpan) }) { Note("Long-press to select, then Restore or Delete forever.") }
                    items(tiles, key = { it.first.id }) { (t, m) ->
                        val isSelected = t.id in selected
                        val days = daysLeft(t.expires, now)
                        val toggle = { selected = if (isSelected) selected - t.id else selected + t.id }
                        MediaTile(
                            m, selecting, isSelected,
                            Modifier.aspectRatio(1f)
                                .combinedClickable(
                                    onLongClickLabel = "Select",
                                    onLongClick = {
                                        haptics.longPress()
                                        selected = selected + t.id
                                    },
                                    hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
                                ) {
                                    when {
                                        selecting -> toggle()
                                        // one at a time: each tap would otherwise queue another
                                        snackbar.currentSnackbarData == null -> scope.launch { snackbar.showSnackbar("Restore it first to open it") }
                                    }
                                }
                                .semantics {
                                    contentDescription = "${if (t.isVideo) "Video" else "Photo"}, deletes in ${counted(days, "day", "days")}"
                                    if (selecting) this.selected = isSelected
                                },
                            badge = "${days}d",
                            badgeTone = BadgeTone.Danger, // time left in Trash, per the design's Badge tones
                        )
                    }
                }
            }
        }
        val still = spec.still
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = if (still) fadeIn(tween(0)) else slideInVertically { it } + fadeIn(),
            exit = if (still) fadeOut(tween(0)) else slideOutVertically { it } + fadeOut(),
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
                onSelectAll = if (chosen.size < trash.size) ({ selected = trash.mapTo(HashSet()) { it.id } }) else null,
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (selecting) selectionBar + Space.s2 else 0.dp),
        ) { AlbumSnackbar(it) }
    }

    toDelete?.let { list ->
        val all = list.size == trash.size
        ConfirmBar(
            title = "Delete ${itemCount(list.size)} forever?",
            body = (if (all) "Recently deleted will be empty. " else "") +
                "${if (list.size == 1) "It" else "They"} will be erased from this phone. This cannot be undone.",
            confirmLabel = "Hold to delete",
            onConfirm = {
                CrashLog.note("delete forever ${list.size}")
                toDelete = null
                done = "Deleted ${itemCount(list.size)} forever"
                request.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, list.map { it.uri })).build())
            },
            onCancel = { toDelete = null },
            icon = Sym.DeleteForever,
            hold = true,
            thumbs = list.take(3).map { it.toMedia() },
        )
    }
}

