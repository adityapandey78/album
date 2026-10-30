package com.aditya.album

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// RAW photos (DNG, what phones' pro modes save) are shown by Android's own decoder: the preview inside the file where
// there's a big enough one, else the raw data itself, developed by Android. That decoder ignores the DNG's orientation, so
// a photo taken upright would lie on its side: Album turns what it decodes upright itself. RAW files are only ever read.

/** Whether [name] is a RAW photo Album shows: DNG, the open RAW format phones write. */
fun isRaw(name: String) = name.endsWith(".dng", ignoreCase = true)

/** MediaStore's type for DNG files. */
const val RAW_MIME = "image/x-adobe-dng"

/** Longest side a RAW photo is shown at in the viewer: sharp on a phone's screen, zoomed in a little too. */
private const val RAW_PX = 4096

/**
 * How to turn a picture decoded from a RAW photo upright: whether to mirror it first, then the degrees clockwise. Android's
 * RAW decoder leaves the DNG's [orientation] unapplied, but what it decoded may already be upright: Android turns a
 * thumbnail it took from the file's embedded JPEG one ([turned]), and a quarter turn also shows when the [decodedWidth] x
 * [decodedHeight] picture already has the raw image's [rawWidth] x [rawHeight] shape turned on its side.
 */
fun rawTurn(orientation: Int, decodedWidth: Int, decodedHeight: Int, rawWidth: Int, rawHeight: Int, turned: Boolean = false): Pair<Boolean, Int> {
    if (turned) return false to 0
    val (mirror, degrees) = uprightTurn(orientation, 0)
    val sideways = degrees % 180 == 90 && rawWidth != rawHeight && decodedWidth != decodedHeight &&
        (decodedWidth > decodedHeight) != (rawWidth > rawHeight)
    return if (sideways) false to 0 else mirror to degrees
}

/**
 * [bitmap], decoded from RAW photo [uri], turned upright (see [rawTurn]). [thumbnail]: it's Android's thumbnail rather
 * than a decode of the file. Unchanged when the file's Exif can't be read. Blocking: call off the main thread.
 */
internal fun uprightRaw(resolver: ContentResolver, uri: Uri, bitmap: Bitmap, thumbnail: Boolean): Bitmap {
    val exif = runCatching { resolver.openFileDescriptor(uri, "r")!!.use { ExifInterface(it.fileDescriptor) } }.getOrNull() ?: return bitmap
    val (mirror, degrees) = rawTurn(
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
        bitmap.width, bitmap.height,
        exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0), exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
        // Android's thumbnailer turns an embedded JPEG thumbnail by the orientation; a full decode it leaves as it comes
        turned = thumbnail && exif.hasThumbnail() && exif.isThumbnailCompressed,
    )
    if (!mirror && degrees == 0) return bitmap
    val matrix = Matrix().apply {
        setScale(if (mirror) -1f else 1f, 1f)
        postRotate(degrees.toFloat())
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false).also { if (it !== bitmap) bitmap.recycle() }
}

/**
 * Coil model for a RAW photo: [thumb] is the grid's (Android's cached thumbnail), else the viewer's. Loaded by
 * [RawFetcher], set per request with [raw], so nothing changes for other photos.
 */
data class RawPhoto(val uri: Uri, val thumb: Boolean, val version: Int = editVersion(uri))

/** Memory cache key of a RAW photo's thumbnail: its own, so a sideways thumbnail from elsewhere can never stand in for it. */
fun rawThumbKey(uri: Uri) = "$uri#${editVersion(uri)}#raw"

/**
 * Makes this request load RAW photo [uri], upright. The grid's thumbnail stays in Coil's memory cache under [rawThumbKey];
 * the viewer's big decode isn't cached, so a few RAW photos can't push every thumbnail out.
 */
fun ImageRequest.Builder.raw(uri: Uri, thumb: Boolean): ImageRequest.Builder = data(RawPhoto(uri, thumb))
    .fetcherFactory(RawFetcher.Factory(), RawPhoto::class)
    .apply { if (thumb) memoryCacheKey(rawThumbKey(uri)) else memoryCachePolicy(CachePolicy.DISABLED) }

class RawFetcher(private val raw: RawPhoto, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val context = options.context
        val bitmap = if (raw.thumb) {
            val size = Size(options.size.width.pxOrElse { 256 }, options.size.height.pxOrElse { 256 })
            uprightRaw(context.contentResolver, raw.uri, context.contentResolver.loadThumbnail(raw.uri, size, null), thumbnail = true)
        } else {
            checkNotNull(decodeUpright(context, raw.uri, RAW_PX)) { "can't decode ${raw.uri}" }
        }
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<RawPhoto> {
        override fun create(data: RawPhoto, options: Options, imageLoader: ImageLoader) = RawFetcher(data, options)
    }
}
