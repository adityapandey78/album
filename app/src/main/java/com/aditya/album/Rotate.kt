package com.aditya.album

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Formats whose Exif orientation can be patched in place. PNG keeps Exif in a checksummed chunk, so patching it would
 * break the file; HEIC/AVIF take their rotation from elsewhere. Those would need re-saving, which Album never does.
 */
private val ROTATABLE = setOf("image/jpeg", "image/webp")

// One photo turned a quarter clockwise at a time, plain and mirrored (the same orders as ExifInterface.rotate)
private val UPRIGHT = listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_ROTATE_270)
private val MIRRORED = listOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_FLIP_VERTICAL, ExifInterface.ORIENTATION_TRANSPOSE)

/**
 * The Exif orientation after turning a photo a quarter [clockwise] or anticlockwise. Mirrored photos stay mirrored.
 * Missing or broken values (0 = undefined) count as upright, unlike ExifInterface.rotate, which leaves those unturned.
 */
fun rotatedOrientation(orientation: Int, clockwise: Boolean): Int {
    val step = if (clockwise) 1 else 3
    val turns = if (orientation in MIRRORED) MIRRORED else UPRIGHT
    return turns[(turns.indexOf(orientation).coerceAtLeast(0) + step) % 4]
}

// How many times Album changed each file in place this session. Read during composition (see Thumb), so every
// thumbnail and viewer page of an edited file reloads.
private val edits = mutableStateMapOf<Uri, Int>()

fun editVersion(uri: Uri) = edits[uri] ?: 0

/**
 * Returns a function that turns a photo a quarter clockwise (true) or anticlockwise without re-encoding it: only the
 * Exif orientation tag changes, after Android's write permission (no pop-up with Media management access).
 * [onMessage] says why when a photo can't be turned (the viewer shows it in its snackbar).
 */
@Composable
fun rememberRotate(onMessage: (String) -> Unit): (Media, Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable so they survive the activity being recreated while Android's pop-up is open
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var clockwise by rememberSaveable { mutableStateOf(false) }
    // Not saved: if the screen is recreated mid-write the write still finishes, and rotate must not stay locked
    var busy by remember { mutableStateOf(false) }
    val write = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uri = pending?.let(Uri::parse)
        pending = null
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            if (!rotate(context, uri, clockwise)) onMessage("Couldn't rotate this photo")
            busy = false
        }
    }
    return rotate@{ m, turnClockwise ->
        if (pending != null || busy) return@rotate // one at a time
        pending = m.uri.toString()
        clockwise = turnClockwise
        scope.launch {
            // Checked before asking for write access, so Android's pop-up never comes up for a photo we'd then refuse
            val refusal = withContext(Dispatchers.IO) { whyNotRotatable(context.contentResolver, m.uri) }
            if (refusal != null) {
                CrashLog.note("rotate refused: $refusal")
                onMessage(refusal)
                pending = null
                return@launch
            }
            CrashLog.note("rotate ${if (turnClockwise) "right" else "left"}")
            write.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, listOf(m.uri))).build())
        }
    }
}

/** Writes and verifies the new orientation, then reloads every cached copy. Finishes even if the viewer closes meanwhile. */
private suspend fun rotate(context: Context, uri: Uri, clockwise: Boolean): Boolean = withContext(NonCancellable + Dispatchers.IO) {
    val resolver = context.contentResolver
    val before = generationOf(resolver, uri)
    try {
        writeOrientation(resolver, uri, clockwise)
    } catch (e: Exception) {
        CrashLog.note("rotate failed: $e")
        return@withContext false
    }
    // People keeps confirmed faces as boxes in the upright photo: turn them with it, or names could swap between faces
    People.rotated(ContentUris.parseId(uri), clockwise)
    // MediaStore rescans the file once it's closed (orientation, size, Android's thumbnail): reload after that
    withTimeoutOrNull(3_000) { Gallery.changes(resolver).first { generationOf(resolver, uri) != before } }
    withContext(Dispatchers.Main) { reloadEdited(context, uri) }
    true
}

/** Drops every cached copy of [uri], which changed on disk, so each thumbnail and viewer page of it reloads. Main thread. */
fun reloadEdited(context: Context, uri: Uri) {
    // the thumbnail and the full photo are both cached under the uri string (ThumbKeyer, Coil's UriKeyer)
    SingletonImageLoader.get(context).memoryCache?.let { cache -> cache.keys.filter { it.key == uri.toString() || it.key.startsWith("$uri#") }.forEach(cache::remove) }
    edits[uri] = editVersion(uri) + 1
}

/** Why [uri] can't be turned by patching its orientation tag in place, or null if it can. */
private fun whyNotRotatable(resolver: ContentResolver, uri: Uri): String? {
    if (resolver.getType(uri) !in ROTATABLE) return "Can't rotate this format without re-saving it"
    val patchable = runCatching {
        resolver.openFileDescriptor(uri, "r")!!.use { ExifInterface(it.fileDescriptor).let { exif -> orientationSlot(exif) != null } }
    }.getOrDefault(false)
    return if (patchable) null else "This photo has no rotation tag to change, so it can't be rotated without re-saving it"
}

/** Where the 2-byte orientation value sits in the file and its stored bytes, or null if missing or not a plain short. */
private fun orientationSlot(exif: ExifInterface): Pair<Long, ByteArray>? {
    val range = exif.getAttributeRange(ExifInterface.TAG_ORIENTATION) ?: return null
    val stored = exif.getAttributeBytes(ExifInterface.TAG_ORIENTATION) ?: return null
    return if (range[1] == 2L && orientationBytes(stored, ExifInterface.ORIENTATION_NORMAL) != null) range[0] to stored else null
}

/**
 * The 2 bytes to store [orientation] (1..8) in the byte order of the [stored] value: big-endian Exif keeps it as
 * (0, v), little-endian as (v, 0). Null when [stored] isn't a valid orientation short, so nothing gets written.
 */
fun orientationBytes(stored: ByteArray, orientation: Int): ByteArray? = when {
    stored.size != 2 || orientation !in 1..8 -> null
    stored[0].toInt() == 0 && stored[1].toInt() in 1..8 -> byteArrayOf(0, orientation.toByte())
    stored[1].toInt() == 0 && stored[0].toInt() in 1..8 -> byteArrayOf(orientation.toByte(), 0)
    else -> null
}

/**
 * Turns [uri]'s Exif orientation a quarter by overwriting just its 2 bytes in place, then reads it back. One tiny
 * write means the photo can never be left half-written (even if the phone dies mid-way), and nothing else in the
 * file moves: pixels, other metadata, a motion photo's video. Throws if anything is off, before writing if possible.
 */
private fun writeOrientation(resolver: ContentResolver, uri: Uri, clockwise: Boolean) {
    val target = resolver.openFileDescriptor(uri, "rw")!!.use { file ->
        val fd = file.fileDescriptor
        val exif = ExifInterface(fd)
        val (offset, stored) = checkNotNull(orientationSlot(exif)) { "no orientation value to patch" }
        val turned = rotatedOrientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL), clockwise)
        // Belt and braces: the 2 bytes at that offset must be the orientation value we're about to replace
        val there = ByteArray(2)
        check(Os.pread(fd, there, 0, 2, offset) == 2 && there.contentEquals(stored)) { "orientation isn't where Exif says it is" }
        val bytes = checkNotNull(orientationBytes(stored, turned))
        check(Os.pwrite(fd, bytes, 0, 2, offset) == 2) { "short write" }
        Os.fsync(fd)
        turned
    }
    val saved = resolver.openFileDescriptor(uri, "r")!!.use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }
    check(saved == target) { "orientation reads back as $saved, not $target" }
}

/** MediaStore's change counter for [uri]: it goes up each time the file is rescanned after a change. */
fun generationOf(resolver: ContentResolver, uri: Uri): Long? =
    resolver.query(uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
