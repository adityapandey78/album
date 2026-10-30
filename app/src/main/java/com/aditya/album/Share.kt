package com.aditya.album

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Remove location when sharing (the details sheet; Settings can show it too): on unless turned off. */
object SharePrefs {
    private const val KEY = "share_strip_location"
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(true)
    val strip = state.asStateFlow()

    /** Safe to call repeatedly; the "settings" prefs are already loading from AlbumApp. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        state.value = prefs.getBoolean(KEY, true)
    }

    fun set(on: Boolean) {
        state.value = on
        prefs.edit { putBoolean(KEY, on) }
    }
}

/** Who took the photo, and with what exactly: left out of shared copies along with the location. */
private val PERSONAL_TAGS = listOf(ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER)

/** Copies are made here, off the main thread, and outlive the screen that shared them. */
private val sharing = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Album's FileProvider reaches only cache/zip; copies shared earlier go when the next share starts. */
private fun shareFolder(context: Context) = File(context.cacheDir, "zip/share")

/**
 * Opens the share sheet for [items]. With Remove location when sharing on (the default) the apps get copies from Album's
 * cache without where they were taken, nor the camera's serial numbers or owner's name; anything that still says where it
 * was taken is left out, and a message says so. Off, they get the files as they are. Callers check [MAX_SHARE].
 */
fun shareMedia(context: Context, items: List<Media>) {
    SharePrefs.load(context)
    if (!SharePrefs.strip.value) {
        context.startSafely(shareIntent(items))
        return
    }
    sharing.launch {
        val copies = runCatching { strippedCopies(context, items) }.onFailure { CrashLog.note("share copies failed: $it") }.getOrNull()
        withContext(Dispatchers.Main) {
            when {
                copies == null -> AlbumOps.say("Couldn't get ${if (items.size == 1) "it" else "them"} ready to share")
                copies.isNotEmpty() -> context.startSafely(copiesIntent(context, copies))
            }
            val left = items.size - (copies?.size ?: items.size)
            if (left > 0) AlbumOps.say("${counted(left, "item", "items")} kept a location Album couldn't remove, so ${if (left == 1) "it was" else "they were"} left out")
        }
    }
}

/** Blocking. [items] copied without their location, in order, leaving out any that still has one. Throws when it can't. */
private fun strippedCopies(context: Context, items: List<Media>): List<Pair<Media, File>> {
    val folder = shareFolder(context).apply { deleteRecursively() }
    check(items.sumOf { it.size } + (50L shl 20) < context.cacheDir.usableSpace) { "no space" }
    return items.mapNotNull { m -> strippedCopy(context, m, folder)?.let { m to it } }
}

/**
 * [m] copied into [folder] without where it was taken, or null when the copy still says. Read plainly, Android already
 * leaves the location out (see Zips); JPEG, PNG and WebP also have their GPS, serial number and owner tags taken out here.
 */
private fun strippedCopy(context: Context, m: Media, folder: File): File? {
    val resolver = context.contentResolver
    // A folder each, so two photos with the same name both go, under their own names
    val file = File(File(folder, "${m.id}").apply { mkdirs() }, m.name.ifEmpty { "${m.id}" }.replace('/', '_'))
    checkNotNull(resolver.openInputStream(m.uri)).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 20) } }
    if (!m.isVideo && runCatching { resolver.getType(m.uri) }.getOrNull() in STRIPPABLE) {
        val exif = ExifInterface(file)
        (GPS_TAGS + PERSONAL_TAGS).forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
    }
    return file.takeUnless { located(it, m.isVideo) }
}

/** Whether [file] still says where it was taken (an all-zero place doesn't count). Unreadable counts as no. */
private fun located(file: File, video: Boolean): Boolean = runCatching {
    if (video) {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709) != null
        }
    } else {
        ExifInterface(file).latLong?.let { it[0] != 0.0 || it[1] != 0.0 } == true
    }
}.getOrDefault(false)

/** The share sheet for [copies], read through Album's FileProvider with a one-off grant. */
private fun copiesIntent(context: Context, copies: List<Pair<Media, File>>): Intent {
    val uris = copies.mapTo(ArrayList<Uri>()) { FileProvider.getUriForFile(context, "${context.packageName}.zip", it.second) }
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    send.type = when {
        copies.all { it.first.isVideo } -> "video/*"
        copies.none { it.first.isVideo } -> "image/*"
        else -> "*/*"
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // So the grant reaches the app picked in the chooser
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    return Intent.createChooser(send, null)
}
