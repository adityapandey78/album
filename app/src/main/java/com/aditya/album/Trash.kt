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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput

/** A photo or video in Android's trash. Android deletes it for good at [expires] (epoch millis). */
data class Trashed(val id: Long, val uri: Uri, val isVideo: Boolean, val expires: Long)

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** "12 days" left before Android deletes a trashed item for good. Rounded up, so a fresh item shows 30 days and never 0. */
fun daysLeft(expires: Long, now: Long): String {
    val days = ((expires - now + DAY_MS - 1) / DAY_MS).coerceAtLeast(1)
    return if (days == 1L) "1 day" else "$days days"
}

fun itemCount(n: Int) = if (n == 1) "1 item" else "$n items"

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
    val projection = arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_EXPIRES)
    val out = ArrayList<Trashed>()
    resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val isVideo = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            out += Trashed(id, mediaUri(id, isVideo), isVideo, expires = c.getLong(2) * 1000) // DATE_EXPIRES is in seconds
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
            val undo = snackbar.showSnackbar("Moved ${itemCount(uris.size)} to trash", "Undo", duration = SnackbarDuration.Long)
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

/** Recently deleted, drawn over the app like Settings. Tap to select, then restore or delete forever. */
@Composable
fun TrashScreen(items: List<Trashed>?, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var toDelete by remember { mutableStateOf<List<Trashed>?>(null) } // waiting for Album's own "are you sure"
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) selected = emptySet()
    }
    val trash = items.orEmpty()
    val chosen = trash.filter { it.id in selected } // ignores ids that left the trash meanwhile
    val selecting = chosen.isNotEmpty()
    val now = remember(items) { System.currentTimeMillis() }

    BackHandler { if (selecting) selected = emptySet() else onBack() }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {} // catches taps on empty space, which would otherwise reach the albums screen underneath
            .systemBarsPadding(),
    ) {
        if (selecting) {
            TitleBar("${chosen.size} selected", backIcon = Icons.Outlined.Close, onBack = { selected = emptySet() }) {
                IconButton(onClick = {
                    if (!context.canSend(chosen.size, MAX_REQUEST, "restore")) return@IconButton
                    CrashLog.note("restore ${chosen.size}")
                    request.launch(IntentSenderRequest.Builder(restoreRequest(context.contentResolver, chosen.map { it.uri })).build())
                }) { Icon(Icons.Outlined.RestoreFromTrash, "Restore") }
                IconButton(onClick = { if (context.canSend(chosen.size, MAX_REQUEST, "delete")) toDelete = chosen }) {
                    Icon(Icons.Outlined.DeleteForever, "Delete forever")
                }
            }
        } else {
            TitleBar(Special.Trash.title, onBack = onBack) {
                if (trash.isNotEmpty()) TextButton(onClick = { if (context.canSend(trash.size, MAX_REQUEST, "delete")) toDelete = trash }) { Text("Empty") }
            }
        }
        when {
            items == null -> {} // first load takes a few ms
            trash.isEmpty() -> Message(Special.Trash.emptyText, Modifier)
            else -> {
                val spec = LocalLook.current
                LazyVerticalGrid(
                    GridCells.Fixed(DEFAULT_COLUMNS),
                    horizontalArrangement = Arrangement.spacedBy(spec.gridGap),
                    verticalArrangement = Arrangement.spacedBy(spec.gridGap),
                ) {
                    items(trash, key = { it.id }) { t ->
                        TrashCell(t, daysLeft(t.expires, now), t.id in selected, selecting) {
                            selected = if (t.id in selected) selected - t.id else selected + t.id
                        }
                    }
                }
            }
        }
    }

    // Album asks itself because with Media management access Android deletes without any pop-up.
    toDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${itemCount(list.size)} forever?") },
            text = { Text("${if (list.size == trash.size) "Recently deleted will be empty. " else ""}This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        CrashLog.note("delete forever ${list.size}")
                        toDelete = null
                        request.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, list.map { it.uri })).build())
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrashCell(t: Trashed, label: String, isSelected: Boolean, selecting: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f)
            .clip(RoundedCornerShape(LocalLook.current.cellCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(Thumb(t.uri), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Text(
            label,
            Modifier.align(Alignment.BottomStart).padding(6.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(shadow = badgeShadow),
        )
        if (t.isVideo) Icon(Icons.Filled.PlayArrow, "Video", Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp), tint = Color.White)
        if (selecting) {
            if (isSelected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            Check(isSelected, ring = Color.White, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

/**
 * Settings row for Android's Media management access. With it, MediaStore's trash, restore and favourite
 * requests complete without a system pop-up: that's why moving to trash offers Undo, and why Delete forever
 * asks inside Album. The switch only shows the state; Android's own settings page changes it.
 */
@Composable
fun ManageMediaRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(MediaStore.canManageMedia(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = MediaStore.canManageMedia(context) // the user changes it in system settings
        onPauseOrDispose {}
    }
    Row(
        Modifier.fillMaxWidth()
            .toggleable(allowed, role = Role.Switch) {
                CrashLog.note("open media management")
                context.startSafely(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")))
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Delete and favourite without pop-ups", style = MaterialTheme.typography.titleMedium)
            Text(
                "Uses Android's Media management access. Moving to trash still offers Undo, and Delete forever still asks first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = allowed, onCheckedChange = null)
    }
}
