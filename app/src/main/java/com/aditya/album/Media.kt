package com.aditya.album

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import android.widget.Toast

/** One photo or video from Android's media index (MediaStore). Read-only: the app never edits files directly. */
data class Media(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val taken: Long, // epoch millis
    val day: Long, // local epoch day, precomputed so grouping on the UI thread stays cheap
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val isFavorite: Boolean,
    val size: Long,
    val name: String,
    val path: String,
)

data class Album(val id: Long, val name: String, val cover: Media, val count: Int)

/** Process-wide photo list. Survives rotation; reloads by itself whenever MediaStore changes. */
object Gallery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** null until the first load finishes. Newest first. */
    val media = MutableStateFlow<List<Media>?>(null)

    /** Call on the main thread once photo permission is granted. Safe to call repeatedly. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            changes(app.contentResolver)
                .conflate() // a burst of changes (camera, bulk delete) collapses into one reload
                .map { load(app.contentResolver) }
                .flowOn(Dispatchers.IO)
                .catch { CrashLog.note("media load failed: $it"); emit(emptyList()) }
                .collect { media.value = it }
        }
    }

    // ponytail: full re-query on every change (~100-300 ms for 20k items on IO). Switch to MediaStore.getGeneration deltas if it shows in profiles.
    /** Emits once now, then after every MediaStore change (including trash, restore and delete). Also drives [rememberTrash]. */
    fun changes(resolver: ContentResolver) = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        trySend(Unit) // initial load
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun load(resolver: ContentResolver): List<Media> {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_TAKEN, FileColumns.DATE_MODIFIED,
            FileColumns.WIDTH, FileColumns.HEIGHT, FileColumns.DURATION, FileColumns.BUCKET_ID,
            FileColumns.BUCKET_DISPLAY_NAME, FileColumns.IS_FAVORITE, FileColumns.SIZE,
            FileColumns.DISPLAY_NAME, FileColumns.RELATIVE_PATH, FileColumns.ORIENTATION,
        )
        val zone = ZoneId.systemDefault()
        val out = ArrayList<Media>()
        resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, PHOTOS_AND_VIDEOS, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isVideo = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
                // DATE_TAKEN can be missing (screenshots from some apps, downloads): fall back to file time
                val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                out += Media(
                    id = id,
                    uri = mediaUri(id, isVideo),
                    isVideo = isVideo,
                    taken = taken,
                    day = Instant.ofEpochMilli(taken).atZone(zone).toLocalDate().toEpochDay(),
                    // cameras often store portrait shots as landscape pixels plus a 90/270 rotation tag
                    width = if (c.getInt(13) % 180 == 90) c.getInt(5) else c.getInt(4),
                    height = if (c.getInt(13) % 180 == 90) c.getInt(4) else c.getInt(5),
                    durationMs = c.getLong(6),
                    bucketId = c.getLong(7),
                    bucketName = c.getString(8) ?: "Other",
                    isFavorite = c.getInt(9) == 1,
                    size = c.getLong(10),
                    name = c.getString(11).orEmpty(),
                    path = c.getString(12).orEmpty(),
                )
            }
        }
        out.sortByDescending { it.taken }
        return out
    }
}

/** SQL selection for photos and videos: the Files collection also holds audio and documents. */
const val PHOTOS_AND_VIDEOS = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"

/** Images or Video collection URI for a row. MediaStore's trash, delete and favourite requests need these, not Files URIs. */
fun mediaUri(id: Long, isVideo: Boolean): Uri =
    ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

/** Albums = folders, Camera first, then the most recently updated. [media] must be newest first. */
fun albumsOf(media: List<Media>): List<Album> =
    media.groupBy { it.bucketId }
        .map { (id, items) -> Album(id, items.first().bucketName, items.first(), items.size) }
        .sortedWith(compareByDescending<Album> { it.name == "Camera" }.thenByDescending { it.cover.taken })

// --- Actions. Each is a MediaStore request, so Android asks first, unless the user has given Album
// Media management access in Settings (then it completes without a pop-up, which is why trash offers Undo).

/** Android 16+ throws for MediaStore requests over 2,000 items, and every request crashes when empty. */
const val MAX_REQUEST = 2000

/** Share sheets fail on very long URI lists (the binder limit), so sharing stops well below that. */
const val MAX_SHARE = 500

/** Whether [count] items can go in one request or share, capped at [max]; if not, says why in a short message. */
fun Context.canSend(count: Int, max: Int, verb: String): Boolean {
    if (count in 1..max) return true
    if (count > max) Toast.makeText(this, "You can $verb up to ${"%,d".format(max)} items at a time", Toast.LENGTH_SHORT).show()
    return false
}

/** Starts [intent], or says so if no app on the phone can handle it, instead of crashing. */
fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }.onFailure {
        CrashLog.note("no activity for ${intent.action}: $it")
        Toast.makeText(this, "No app on this phone can do that", Toast.LENGTH_SHORT).show()
    }
}

fun trashRequest(resolver: ContentResolver, items: Collection<Media>): PendingIntent =
    MediaStore.createTrashRequest(resolver, items.map { it.uri }, true)

fun favoriteRequest(resolver: ContentResolver, items: Collection<Media>, favorite: Boolean): PendingIntent =
    MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, favorite)

fun shareIntent(items: Collection<Media>): Intent {
    val uris = ArrayList(items.map { it.uri })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    send.type = when {
        items.all { it.isVideo } -> "video/*"
        items.none { it.isVideo } -> "image/*"
        else -> "*/*"
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, null)
}

// --- Grid thumbnails come from Android's own thumbnail cache: much faster than decoding a 50 MP photo.

/** Coil model for a grid thumbnail. */
data class Thumb(val uri: Uri)

class ThumbFetcher(private val thumb: Thumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val size = Size(options.size.width.pxOrElse { 256 }, options.size.height.pxOrElse { 256 })
        val bitmap = options.context.contentResolver.loadThumbnail(thumb.uri, size, null)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader) = ThumbFetcher(data, options)
    }
}

/** Lets Coil keep thumbnails in its memory cache, so scrolling back is instant. */
class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options) = data.uri.toString()
}
