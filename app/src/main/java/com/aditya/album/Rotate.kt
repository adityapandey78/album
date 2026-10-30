package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.app.Activity
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.system.Os
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.exifinterface.media.ExifInterface
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

// Rotate turns a photo a quarter at a time. Its original changes in place only when that loses nothing: a JPEG or WebP
// whose Exif orientation value can be patched (2 bytes). Every other photo, and every "Save as a copy", gets a rotated
// copy next to it. Originals are never re-encoded or overwritten.

/**
 * Formats whose Exif orientation can be changed in place. PNG keeps Exif in a checksummed chunk, so patching it would
 * break the file; HEIC/AVIF take their rotation from elsewhere. Those get a rotated copy instead.
 */
private val PATCHABLE = setOf("image/jpeg", "image/webp")

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

/** The Exif orientation after turning a photo [turns] quarters clockwise (1 to 3). */
private fun turnedOrientation(orientation: Int, turns: Int) = (1..turns).fold(orientation) { tag, _ -> rotatedOrientation(tag, clockwise = true) }

/**
 * How to draw a photo stored with Exif [orientation] upright and then turned [quarters] clockwise: whether to mirror it
 * (left to right) first, then the degrees to turn it clockwise, 0 to 270. Missing or broken values count as upright.
 */
fun uprightTurn(orientation: Int, quarters: Int): Pair<Boolean, Int> {
    val (mirror, degrees) = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> true to 0
        ExifInterface.ORIENTATION_ROTATE_180 -> false to 180
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> true to 180
        ExifInterface.ORIENTATION_TRANSPOSE -> true to 270
        ExifInterface.ORIENTATION_ROTATE_90 -> false to 90
        ExifInterface.ORIENTATION_TRANSVERSE -> true to 90
        ExifInterface.ORIENTATION_ROTATE_270 -> false to 270
        else -> false to 0
    }
    return mirror to Math.floorMod(degrees + quarters * 90, 360)
}

// How many times Album changed each file in place this session. Read during composition (see Thumb), so every
// thumbnail and viewer page of an edited file reloads.
private val edits = mutableStateMapOf<Uri, Int>()

fun editVersion(uri: Uri) = edits[uri] ?: 0

// ---------- How Save saves ----------

/** What Save does in rotate mode: picked in its sheet with "Don't ask again", or in Settings. [label] is Settings' wording. */
enum class RotateSave(val label: String) { Ask("Ask each time"), Copy("Save a copy"), Original("Update original") }

/** The [RotateSave] choice, in the "settings" prefs. */
object RotatePrefs {
    private const val KEY = "rotate_save"
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(RotateSave.Ask)
    val choice = state.asStateFlow()

    /** Safe to call repeatedly; the "settings" prefs are already loading from AlbumApp. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        state.value = RotateSave.entries.find { it.name == prefs.getString(KEY, null) } ?: RotateSave.Ask
    }

    fun set(choice: RotateSave) {
        state.value = choice
        prefs.edit { putString(KEY, choice.name) }
    }
}

/**
 * Where Save goes with [choice] for a photo whose original can ([inPlace]) or can't be turned in place: the choice itself,
 * except that Update original becomes a copy where it can't. [RotateSave.Ask] means the sheet asks.
 */
fun saveTarget(choice: RotateSave, inPlace: Boolean): RotateSave =
    if (choice == RotateSave.Original && !inPlace) RotateSave.Copy else choice

// ---------- In place ----------

/** How turning an original went. [Cancelled]: Android's pop-up was declined. [Failed] leaves the file as it was. */
enum class Turned { Done, Cancelled, Failed }

/**
 * Returns a function that turns a photo's original by quarter turns, clockwise for positive counts, without re-encoding
 * it: only the Exif orientation tag changes, after Android's write permission (no pop-up with Media management access).
 * Only for photos [turnsInPlace] says yes to. Each call's `done` hears how it went.
 */
@Composable
fun rememberRotate(): (m: Media, quarters: Int, done: (Turned) -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable so they survive the activity being recreated while Android's pop-up is open
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var turns by rememberSaveable { mutableIntStateOf(0) }
    // Not saved: if the screen is recreated mid-write the write still finishes, and rotate must not stay locked.
    // A recreated screen has no rotate mode left to finish, so losing [finish] then is fine.
    var busy by remember { mutableStateOf(false) }
    var finish by remember { mutableStateOf<((Turned) -> Unit)?>(null) }
    fun finished(result: Turned) {
        finish?.invoke(result)
        finish = null
    }
    val write = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uri = pending?.let(Uri::parse)
        pending = null
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult finished(Turned.Cancelled)
        busy = true
        scope.launch {
            val ok = rotate(context, uri, turns)
            busy = false
            finished(if (ok) Turned.Done else Turned.Failed)
        }
    }
    return rotate@{ m, quarters, done ->
        if (pending != null || busy) return@rotate done(Turned.Cancelled) // one at a time
        val clockwiseTurns = Math.floorMod(quarters, 4)
        if (clockwiseTurns == 0) return@rotate done(Turned.Done) // a full turn changes nothing
        pending = m.uri.toString()
        turns = clockwiseTurns
        finish = done
        scope.launch {
            // Checked again before asking for write access, so Android's pop-up never comes up for a photo we'd then refuse
            if (!withContext(Dispatchers.IO) { turnsInPlace(context.contentResolver, m.uri) }) {
                CrashLog.note("rotate refused: can't patch")
                pending = null
                return@launch finished(Turned.Failed)
            }
            CrashLog.note("rotate ${listOf("", "right", "half", "left")[clockwiseTurns]}")
            write.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, listOf(m.uri))).build())
        }
    }
}

/**
 * Writes and verifies the new orientation, [turns] quarters clockwise (1 to 3), then reloads every cached copy.
 * Finishes even if the viewer closes meanwhile.
 */
private suspend fun rotate(context: Context, uri: Uri, turns: Int): Boolean = withContext(NonCancellable + Dispatchers.IO) {
    val resolver = context.contentResolver
    val before = generationOf(resolver, uri)
    try {
        writeOrientation(resolver, uri, turns)
    } catch (e: Exception) {
        CrashLog.note("rotate failed: $e")
        return@withContext false
    }
    // People keeps confirmed faces as boxes in the upright photo: turn them with it, or names could swap between faces
    val id = ContentUris.parseId(uri)
    if (turns == 3) People.rotated(id, clockwise = false) else repeat(turns) { People.rotated(id, clockwise = true) }
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

/** How a rotated copy is made: [Patch] and [Retag] keep every pixel as it is; [Resave] decodes the photo and encodes it again. */
enum class CopyWay { Patch, Retag, Resave }

/**
 * How a photo of type [mime] is turned without loss: patching its orientation value where it has one to patch
 * ([patchable]), which is also the only way its original can change in place; else, for JPEG and WebP, writing it a new
 * orientation tag in a copy; anything else is re-saved.
 */
fun copyWay(mime: String?, patchable: Boolean): CopyWay = when {
    mime !in PATCHABLE -> CopyWay.Resave
    patchable -> CopyWay.Patch
    else -> CopyWay.Retag
}

/** Whether [uri]'s original can be turned in place, losing nothing (see [copyWay]). Blocking: call off the main thread. */
fun turnsInPlace(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
    resolver.getType(uri) in PATCHABLE && resolver.openFileDescriptor(uri, "r")!!.use { orientationSlot(ExifInterface(it.fileDescriptor)) != null }
}.getOrDefault(false)

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
 * Turns [uri]'s Exif orientation [turns] quarters clockwise by overwriting just its 2 bytes in place, then reads it
 * back. One tiny write means the photo can never be left half-written (even if the phone dies mid-way), and nothing
 * else in the file moves: pixels, other metadata, a motion photo's video. Throws if anything is off, before writing if
 * possible.
 */
private fun writeOrientation(resolver: ContentResolver, uri: Uri, turns: Int) {
    var slot: Pair<Long, ByteArray>? = null // where the 2 bytes are and what they were, to put back if the check fails
    val target = resolver.openFileDescriptor(uri, "rw")!!.use { file ->
        val fd = file.fileDescriptor
        val exif = ExifInterface(fd)
        val (offset, stored) = checkNotNull(orientationSlot(exif)) { "no orientation value to patch" }
        slot = offset to stored
        val turned = turnedOrientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL), turns)
        // Belt and braces: the 2 bytes at that offset must be the orientation value we're about to replace
        val there = ByteArray(2)
        check(Os.pread(fd, there, 0, 2, offset) == 2 && there.contentEquals(stored)) { "orientation isn't where Exif says it is" }
        val bytes = checkNotNull(orientationBytes(stored, turned))
        check(Os.pwrite(fd, bytes, 0, 2, offset) == 2) { "short write" }
        Os.fsync(fd)
        turned
    }
    val saved = resolver.openFileDescriptor(uri, "r")!!.use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }
    if (saved != target) {
        // Put the original 2 bytes back, so a rotate that failed its check leaves the photo exactly as it was
        slot?.let { (offset, stored) ->
            resolver.openFileDescriptor(uri, "rw")!!.use {
                Os.pwrite(it.fileDescriptor, stored, 0, 2, offset)
                Os.fsync(it.fileDescriptor)
            }
        }
        error("orientation reads back as $saved, not $target")
    }
}

/** MediaStore's change counter for [uri]: it goes up each time the file is rescanned after a change. */
fun generationOf(resolver: ContentResolver, uri: Uri): Long? =
    resolver.query(uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }

// ---------- Rotated copies ----------

/** Room left over after saving, so a copy never fills the phone up. */
private const val SPARE = 50L shl 20

private val ROTATED_SUFFIX = Regex("""_rotated(?: \(\d+\))?$""")

/**
 * The name for a rotated copy of [name] saved as [extension]: "IMG_20241024_140512.jpg" becomes
 * "IMG_20241024_140512_rotated.jpg". A copy of a copy doesn't pile up "_rotated"s; clashes are left to [freeNames].
 */
fun rotatedName(name: String, extension: String): String {
    val stem = name.substringBeforeLast('.').replace(ROTATED_SUFFIX, "").ifEmpty { "Photo" }
    return "${stem}_rotated.$extension"
}

/** The smallest power-of-two downsampling that brings a [width] x [height] photo within [maxPixels]: 1 keeps it full size. */
fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
    var sample = 1
    while ((width / sample).toLong() * (height / sample) > maxPixels) sample *= 2
    return sample
}

/**
 * Most pixels a photo may be decoded at to be re-saved turned: it and its turned copy (4 bytes a pixel each) must fit in
 * a quarter of the memory the phone has [available]. Never more than 100 MP, and at least 12 MP.
 */
fun pixelBudget(available: Long): Long = (available / 4 / 8).coerceIn(12_000_000L, 100_000_000L)

/** How [saveRotatedCopy] went: the new photo, or words for why not. */
sealed interface RotatedCopy {
    /** [album] is the folder it went to, as albums are named; [megapixels] only when it had to be saved smaller than the original. */
    class Saved(val uri: Uri, val album: String, val megapixels: Int?) : RotatedCopy

    class Failed(val message: String) : RotatedCopy
}

/**
 * Saves [m] turned [quarters] clockwise as a new photo next to it (or in Rotated where Album saves, if it can't add photos there),
 * with its date taken, camera details, place and favourite. The original is only ever read. JPEG and WebP copies keep
 * every pixel and only get a new orientation tag (see [copyWay]); other photos are decoded upright, turned and saved
 * again: PNG, GIF (its first frame) and anything with transparency as PNG, which loses nothing, the rest as JPEG at
 * quality 95. Finishes even if the viewer closes meanwhile.
 */
suspend fun saveRotatedCopy(context: Context, m: Media, quarters: Int): RotatedCopy = withContext(NonCancellable + Dispatchers.IO) {
    val resolver = context.contentResolver
    val turns = Math.floorMod(quarters, 4)
    // Without this Android blanks the GPS tags in what it reads out, and the copy would lose where it was taken
    val source = if (context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) MediaStore.setRequireOriginal(m.uri) else m.uri
    // Next to the original means on its storage too, which may be an SD card. WhatsApp's folder and Download can't take
    // photos from Album, so those copies go to Rotated where Album saves.
    val to = if (canHold(m.path, isVideo = false)) SaveLocation(volumeOf(resolver, m.uri), m.path) else SavePrefs.target(context).inside("Rotated")
    val album = to.folder.trimEnd('/').substringAfterLast('/')
    val taken = Gallery.media.value.orEmpty().filter { folderKey(it.path) == folderKey(to.folder) }.map { it.name }
    fun nameAs(extension: String) = freeNames(listOf(rotatedName(m.name, extension)), taken).single()
    val scratch = File(context.cacheDir, "rotate-${m.id}.tmp")
    try {
        // The copy, and for a retagged or re-saved one a scratch file first; re-saved pixels can take more room than the original
        if (context.filesDir.usableSpace < 2 * maxOf(m.size, m.width.toLong() * m.height * 2) + SPARE) {
            return@withContext RotatedCopy.Failed("Not enough space to save a rotated copy")
        }
        val mime = resolver.getType(m.uri)
        // A copy that keeps every pixel keeps the original's extension too ("JPG", "jpeg"...)
        val extension = m.name.substringAfterLast('.', "").ifEmpty { if (mime == "image/webp") "webp" else "jpg" }
        when (copyWay(mime, turnsInPlace(resolver, m.uri))) {
            CopyWay.Patch -> {
                val uri = publish(resolver, m, nameAs(extension), to, mime!!) { copy ->
                    pour(checkNotNull(resolver.openInputStream(source)), resolver, copy)
                    writeOrientation(resolver, copy, turns) // the same 2-byte patch an original gets, on the copy
                }
                RotatedCopy.Saved(uri, album, null)
            }
            CopyWay.Retag, CopyWay.Resave -> {
                val retagged = mime in PATCHABLE && retag(resolver, source, scratch, turns)
                val resaved = if (retagged) null else resave(context, source, mime, scratch, turns)
                val uri = publish(resolver, m, nameAs(resaved?.extension ?: extension), to, resaved?.mime ?: mime!!) { copy ->
                    pour(scratch.inputStream(), resolver, copy)
                }
                RotatedCopy.Saved(uri, album, resaved?.megapixels)
            }
        }
    } catch (e: Exception) {
        CrashLog.note("rotated copy failed: $e")
        RotatedCopy.Failed("Couldn't save a rotated copy")
    } catch (e: OutOfMemoryError) {
        CrashLog.note("rotated copy out of memory: ${m.width} x ${m.height}")
        RotatedCopy.Failed("Not enough memory to save a rotated copy")
    } finally {
        scratch.delete()
    }
}

/** Writes all of [input] into [uri], then closes both. */
internal fun pour(input: InputStream, resolver: ContentResolver, uri: Uri) = input.use {
    // 1 MB chunks: the default 8 KB means hundreds of thousands of trips through Android's storage layer for a big photo
    checkNotNull(resolver.openOutputStream(uri, "w")).use { out -> it.copyTo(out, 1 shl 20) }
}

/**
 * Adds a photo called [name] to [to] through MediaStore and fills it with [write]. The row stays pending (invisible
 * to other apps) until every byte is written; if anything fails it's deleted again. Keeps [m]'s date taken and favourite.
 */
internal fun publish(resolver: ContentResolver, m: Media, name: String, to: SaveLocation, mime: String, write: (Uri) -> Unit): Uri {
    val values = ContentValues().apply {
        put(MediaColumns.DISPLAY_NAME, name)
        put(MediaColumns.RELATIVE_PATH, to.folder)
        put(MediaColumns.MIME_TYPE, mime)
        put(MediaColumns.DATE_TAKEN, m.taken) // keeps the copy next to the original in the timeline
        put(MediaColumns.IS_PENDING, 1)
    }
    var copy: Uri? = null
    try {
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(to.volume), values)).also { copy = it }
        write(uri)
        check(resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        // Publishing rescans the file and recomputes DATE_TAKEN, from its own clock where it has no Exif date: put the
        // original's back. Best effort, like the favourite: the copy itself is already complete.
        runCatching { resolver.update(uri, ContentValues().apply { put(MediaColumns.DATE_TAKEN, m.taken) }, null, null) }
        if (m.isFavorite) {
            runCatching { resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_FAVORITE, 1) }, null, null) }
                .onFailure { CrashLog.note("copy favourite failed: $it") }
        }
        return uri
    } catch (e: Throwable) {
        copy?.let { runCatching { resolver.delete(it, null, null) } }
        throw e
    }
}

/**
 * Writes [source], a JPEG or WebP with no orientation value to patch (WhatsApp's), to [scratch] with a new orientation
 * tag turned [turns] quarters. Exif is rewritten but the pixels are copied as they are, so nothing is lost. False if the
 * result doesn't read back as it should (then the photo is re-saved instead).
 */
private fun retag(resolver: ContentResolver, source: Uri, scratch: File, turns: Int): Boolean = runCatching {
    checkNotNull(resolver.openInputStream(source)).use { input -> scratch.outputStream().use { input.copyTo(it, 1 shl 20) } }
    val exif = ExifInterface(scratch)
    val target = turnedOrientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL), turns)
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, "$target")
    exif.saveAttributes()
    ExifInterface(scratch).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) == target && decodes(scratch)
}.onFailure { CrashLog.note("retag failed: $it") }.getOrDefault(false)

/** What [resave] wrote: its type and extension, and its size in megapixels if that's smaller than the original's. */
private class Resaved(val mime: String, val extension: String, val megapixels: Int?)

/**
 * Decodes [source] upright (its Exif orientation applied), turns it [turns] quarters clockwise and saves it to [scratch]
 * with the original's date, camera and place details. A photo too big for the memory the phone has free is decoded at a
 * half, a quarter... of its size (see [pixelBudget]); everything else keeps every pixel.
 */
private fun resave(context: Context, source: Uri, mime: String?, scratch: File, turns: Int): Resaved {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "can't read the photo's size" }
    val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    val sample = sampleSize(bounds.outWidth, bounds.outHeight, pixelBudget(memory.availMem))
    // Formats without Exif (GIF, BMP) have nothing to read: upright as stored, nothing to carry over
    val exif = runCatching { resolver.openFileDescriptor(source, "r")!!.use { ExifInterface(it.fileDescriptor) } }.getOrNull()
    val decoded = checkNotNull(resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }) {
        "can't decode the photo"
    }
    // An Ultra HDR gain map wouldn't be turned with the photo, so the copy is plain SDR rather than lit up wrongly
    if (Build.VERSION.SDK_INT >= 34 && decoded.hasGainmap()) runCatching { decoded.gainmap = null }
    val (mirror, degrees) = uprightTurn(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL, turns)
    val matrix = Matrix().apply {
        setScale(if (mirror) -1f else 1f, 1f)
        postRotate(degrees.toFloat())
    }
    // No filtering: quarter turns move whole pixels, so nothing is resampled
    val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, false)
    if (turned !== decoded) decoded.recycle()
    val png = mime == "image/png" || mime == "image/gif" || turned.hasAlpha()
    val megapixels = if (sample > 1) (turned.width.toLong() * turned.height / 1_000_000.0).roundToInt() else null
    try {
        scratch.outputStream().use {
            check(turned.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 95, it)) { "can't encode the photo" }
        }
    } finally {
        turned.recycle()
    }
    exif?.let { carryExif(it, scratch) }
    check(decodes(scratch)) { "the re-saved photo doesn't read back" }
    return if (png) Resaved("image/png", "png", megapixels) else Resaved("image/jpeg", "jpg", megapixels)
}

// Exif a re-saved copy keeps: when, with which camera and settings, and who made it. Where it was is added when known.
private val CARRIED = listOf(
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
)
private val PLACE = listOf(
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
)

/** Copies [from]'s details into the re-saved photo [to], marked upright. Best effort: the copy is fine without, and MediaStore keeps its date. */
internal fun carryExif(from: ExifInterface, to: File) = runCatching {
    val exif = ExifInterface(to)
    // Without location access Android hands out zeros, which aren't a place
    val located = from.latLong?.let { it[0] != 0.0 || it[1] != 0.0 } == true
    (if (located) CARRIED + PLACE else CARRIED).forEach { tag -> from.getAttribute(tag)?.let { exif.setAttribute(tag, it) } }
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, "${ExifInterface.ORIENTATION_NORMAL}")
    exif.saveAttributes()
}.onFailure { CrashLog.note("copy exif failed: $it") }

/** Whether [file] reads as a picture. */
private fun decodes(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outWidth > 0
