package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Share as ZIP: one file holding a selection, an album or a group (a folder per album), for apps that take one file
// better than fifty. Photos and videos go in stored, not compressed again (they're compressed already), and stream
// through, so a big selection never needs the memory to hold it. The ZIP is made in Album's cache and shared from there,
// or written straight into a folder the user picks.

/** One folder in a ZIP: [items] go in [folder], or at the top when it's null. */
class ZipPart(val folder: String?, val items: List<Media>)

/** Where a ZIP goes: Album's cache, to share, or a document the user made in a folder they picked. */
sealed interface ZipTarget {
    class Share(val file: File) : ZipTarget
    class Document(val uri: Uri) : ZipTarget
}

private val UNSAFE = Regex("""[/\\:*?"<>|\u0000-\u001f]""")

/** [name] as a file or folder name any computer takes: no path characters, no leading dot, no trailing dots or spaces. */
fun safeFileName(name: String): String = name.replace(UNSAFE, "_").trim().trimStart('.').trimEnd('.', ' ').ifEmpty { "Album" }

/**
 * The ZIP's name: the album's ("Goa 2024.zip"), or for a selection the dates of its photos ([taken], epoch millis):
 * "Photos 24 Oct 2026.zip" for one day, then "Photos Oct 2026.zip", "Photos 2026.zip" or "Photos 2019 to 2026.zip".
 */
fun zipName(title: String?, taken: List<Long>, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
    if (title != null) return "${safeFileName(title)}.zip"
    val days = taken.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val first = days.minOrNull() ?: return "Photos.zip"
    val last = days.max()
    fun f(pattern: String) = first.format(DateTimeFormatter.ofPattern(pattern, locale))
    val dates = when {
        first == last -> f("d MMM yyyy")
        first.year == last.year && first.month == last.month -> f("MMM yyyy")
        first.year == last.year -> "${first.year}"
        else -> "${first.year} to ${last.year}"
    }
    return "Photos $dates.zip"
}

/**
 * Each item's path inside the ZIP: its file name ([nameOf]), under its part's folder (first) when it has one. Folder
 * names are made safe and kept apart (two albums called Goa become "Goa" and "Goa (1)"); file names clash only within a
 * folder, and get " (1)" before the extension like files arriving on the phone ([freeNames]).
 */
fun <T> zipPaths(parts: List<Pair<String?, List<T>>>, nameOf: (T) -> String): List<Pair<String, T>> {
    val usedFolders = HashSet<String>()
    return parts.flatMap { (folder, items) ->
        val inZip = folder?.let { safeFileName(it) }?.let { f ->
            var name = f
            var n = 1
            while (!usedFolders.add(name.lowercase())) name = "$f (${n++})"
            name
        }
        val names = freeNames(items.map { safeFileName(nameOf(it)) }, emptyList())
        names.zip(items) { name, item -> (if (inZip != null) "$inZip/$name" else name) to item }
    }
}

/** The ZIP's line on the sheet: "Goa 2024.zip · 24 items · 1.2 GB". */
private fun zipSummary(context: Context, name: String, items: Int, bytes: Long) =
    "$name · ${itemCount(items)} · ${Formatter.formatShortFileSize(context, bytes)}"

/** Formats whose location Album can take out of a copy itself, if Android left it in: their Exif is rewritten, pixels untouched. */
internal val STRIPPABLE = setOf("image/jpeg", "image/png", "image/webp")

internal val GPS_TAGS = listOf(
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
)

/** Room left over after a ZIP in the cache, so sharing never fills the phone up. */
private const val SPARE = 50L shl 20

/** How long a shared ZIP stays in the cache: the app it went to may read it a while later (Gmail does when it sends). */
private const val KEEP_MS = 60 * 60 * 1000L

private class Stopped : Exception()

/** ZIPs in the cache, and the job that makes them (see [AlbumOps.runJob]). */
object Zips {
    /** ZIPs ready to share, held until Album is on screen: Android doesn't let an app in the background open the share sheet. */
    val ready = Channel<File>(Channel.UNLIMITED)

    private fun folder(context: Context) = File(context.cacheDir, "zip")

    /** A file for a ZIP called [name] in the cache, after deleting every earlier one. */
    fun newFile(context: Context, name: String): File {
        folder(context).listFiles()?.forEach { it.delete() }
        return File(folder(context).apply { mkdirs() }, name)
    }

    /** Deletes ZIPs shared more than an hour ago. Android also clears the cache by itself when space runs low. */
    fun clean(context: Context) {
        val old = System.currentTimeMillis() - KEEP_MS
        folder(context).listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    }

    /** The share sheet for [file], read through Album's FileProvider (it never leaves the cache otherwise). */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.zip", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri) // so the grant reaches the app picked in the chooser
        return Intent.createChooser(send, null)
    }

    /**
     * Makes the ZIP of [parts] into [target]. With [keepLocation] false (the default on the sheet) every photo and video
     * goes in without where it was taken; with it true they go in exactly as they are on the phone.
     */
    fun start(context: Context, name: String, parts: List<ZipPart>, keepLocation: Boolean, target: ZipTarget) = AlbumOps.runJob {
        val app = context.applicationContext
        val paths = zipPaths(parts.map { it.folder to it.items }, Media::name)
        val bytes = paths.sumOf { it.second.size }
        if (target is ZipTarget.Share && (app.cacheDir.usableSpace < bytes + SPARE)) {
            return@runJob AlbumOps.Message("Not enough space to make $name. It needs about ${Formatter.formatShortFileSize(app, bytes)}.")
        }
        CrashLog.note("zip ${paths.size} ${if (keepLocation) "with" else "without"} location")
        var keep = false
        try {
            val result = openTarget(app, target).use { out -> write(app, paths, keepLocation, ZipOutputStream(out.buffered(1 shl 20)), bytes) }
            keep = result.added > 0
            val skipped = listOfNotNull(
                result.unread.takeIf { it > 0 }?.let { "${counted(it, "item", "items")} couldn't be read" },
                result.located.takeIf { it > 0 }?.let { "${counted(it, "item", "items")} kept a location Album couldn't remove, so ${if (it == 1) "it was" else "they were"} left out" },
            ).joinToString(". ").ifEmpty { null }
            when {
                result.added == 0 -> AlbumOps.Message("Couldn't make $name. ${skipped.orEmpty()}".trim())
                target is ZipTarget.Share -> {
                    Zips.ready.trySend(target.file)
                    skipped?.let { AlbumOps.Message("$it.") }
                }
                else -> AlbumOps.Message("Saved $name${skipped?.let { ". $it" }.orEmpty()}")
            }
        } catch (e: Stopped) {
            AlbumOps.Message("Stopped. Nothing was saved.")
        } finally {
            // An unfinished or empty ZIP is no use to anyone, so it never stays behind, in the cache or in the folder picked
            if (!keep) when (target) {
                is ZipTarget.Share -> target.file.delete()
                is ZipTarget.Document -> runCatching { DocumentsContract.deleteDocument(app.contentResolver, target.uri) }
            }
        }
    }

    private fun openTarget(context: Context, target: ZipTarget): OutputStream = when (target) {
        is ZipTarget.Share -> target.file.outputStream()
        is ZipTarget.Document -> checkNotNull(context.contentResolver.openOutputStream(target.uri, "w")) { "can't write the ZIP" }
    }

    private class Written(val added: Int, val unread: Int, val located: Int)

    /**
     * Writes every item in [paths] to [zip] stored as it is. A stored entry needs its size and CRC before its bytes, so each
     * file is read twice: once to measure, once into the ZIP. The progress line moves by bytes over both reads.
     */
    private fun write(context: Context, paths: List<Pair<String, Media>>, keepLocation: Boolean, zip: ZipOutputStream, bytes: Long): Written {
        val total = (2 * bytes).coerceAtLeast(1)
        var read = 0L
        var shown = -1f
        var added = 0
        var unread = 0
        var located = 0
        val scratch = File(context.cacheDir, "zip-scratch.tmp")
        zip.use {
            for ((i, entry) in paths.withIndex()) {
                if (AlbumOps.stopped) throw Stopped()
                val (path, m) = entry
                val tick = { n: Int ->
                    if (AlbumOps.stopped) throw Stopped()
                    read += n
                    val fraction = (read.toFloat() / total).coerceIn(0f, 1f)
                    // A new item, or the line visibly further on: not a redraw for every megabyte
                    if (n == 0 || fraction - shown >= 0.005f) {
                        shown = fraction
                        AlbumOps.progress.value = AlbumOps.Progress("Zipping", i + 1, paths.size, STOP_NOTE, fraction)
                    }
                }
                tick(0)
                try {
                    val source = sourceOf(context, m, keepLocation, scratch)
                    if (source == null) {
                        located++
                        continue
                    }
                    val crc = CRC32()
                    var size = 0L
                    source().use { input -> pump(input, { b, n -> crc.update(b, 0, n); size += n }, tick) }
                    zip.putNextEntry(ZipEntry(path).apply {
                        method = ZipEntry.STORED
                        this.size = size
                        compressedSize = size
                        this.crc = crc.value
                        time = m.taken // unzipped files keep the day they were taken
                    })
                    source().use { input -> pump(input, { b, n -> zip.write(b, 0, n) }, tick) }
                    zip.closeEntry() // checks size and CRC: a file that changed between the two reads fails here, not silently
                    added++
                } catch (e: Stopped) {
                    throw e
                } catch (e: java.util.zip.ZipException) {
                    throw e // the ZIP itself is broken now; the whole job fails and it's deleted
                } catch (e: Exception) {
                    CrashLog.note("zip item failed: $e")
                    unread++
                } finally {
                    scratch.delete()
                }
            }
        }
        return Written(added, unread, located)
    }

    /** Copies [input] in 1 MB chunks to [sink], reporting each chunk to [tick] (which throws on Stop). */
    private fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit, tick: (Int) -> Unit) {
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            sink(buffer, n)
            tick(n)
        }
    }

    /**
     * How to read [m] for the ZIP, or null to leave it out. Read plainly, Android takes the location out of what it hands
     * over (same length, nothing else changed); only [keepLocation] asks for the original. As a check, a photo that
     * still reads with a location goes in through [scratch] with its GPS tags taken out (its Exif rewritten, the pixels
     * copied as they are); a video, or a photo in another format, that still has one is left out.
     */
    private fun sourceOf(context: Context, m: Media, keepLocation: Boolean, scratch: File): (() -> InputStream)? {
        val resolver = context.contentResolver
        val canKeep = context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (keepLocation && canKeep) {
            val original = MediaStore.setRequireOriginal(m.uri)
            return { checkNotNull(resolver.openInputStream(original)) }
        }
        val plain = { checkNotNull(resolver.openInputStream(m.uri)) }
        if (!stillLocated(context, m)) return plain
        if (m.isVideo || resolver.getType(m.uri) !in STRIPPABLE) return null
        plain().use { input -> scratch.outputStream().use { input.copyTo(it, 1 shl 20) } }
        val exif = ExifInterface(scratch)
        GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
        if (ExifInterface(scratch).latLong != null) return null
        return { scratch.inputStream() }
    }

    /** Whether [m], read plainly, still says where it was taken (an all-zero place, as Android leaves it, doesn't count). */
    private fun stillLocated(context: Context, m: Media): Boolean = runCatching {
        if (m.isVideo) {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, m.uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709) != null
            }
        } else {
            context.contentResolver.openFileDescriptor(m.uri, "r")!!.use { fd ->
                ExifInterface(fd.fileDescriptor).latLong?.let { it[0] != 0.0 || it[1] != 0.0 } == true
            }
        }
    }.getOrDefault(false) // unreadable here means unreadable for the ZIP too, which counts it then
}

private const val STOP_NOTE = "Stopping deletes the unfinished ZIP. Your photos aren't touched."

/**
 * Share as ZIP's sheet: the ZIP's name, count and size, whether to remove location (on, as sharing should be private by
 * default; only offered when Album may read locations at all, since without that Android always removes them), then
 * Save to a folder or Share.
 */
@Composable
fun ZipSheet(name: String, parts: List<ZipPart>, onDismiss: () -> Unit, onStart: (save: Boolean, keepLocation: Boolean) -> Unit) {
    val context = LocalContext.current
    val canKeep = remember { context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED }
    var removeLocation by rememberSaveable { mutableStateOf(true) }
    val items = remember(parts) { parts.flatMap { it.items } }
    Sheet(onDismiss, "Share as ZIP") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Text(zipSummary(context, name, items.size, items.sumOf { it.size }), style = AlbumTheme.type.caption, color = AlbumTheme.colors.inkMuted)
            if (canKeep) {
                SwitchRow(
                    "Remove location",
                    if (removeLocation) "Where they were taken is left out of the ZIP. Your photos keep it." else "Photos go in exactly as they are, with where they were taken.",
                    removeLocation,
                    { removeLocation = it },
                    icon = Sym.LocationOff,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                AlbumButton("Save to a folder", { onStart(true, canKeep && !removeLocation) }, Modifier.weight(1f).heightIn(min = 56.dp))
                AlbumButton("Share", { onStart(false, canKeep && !removeLocation) }, Modifier.weight(1f).heightIn(min = 56.dp), ButtonVariant.Primary, Sym.Share)
            }
        }
    }
}
