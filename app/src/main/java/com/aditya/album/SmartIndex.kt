package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.Size
import androidx.core.content.edit
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Bump when analysis changes (labels, category mapping, place data, text) so every item is analysed again. */
private const val INDEX_VERSION = 2
private const val BATCH = 32 // items per database transaction; also how often progress moves
private const val THUMB_PX = 512 // ML Kit labels from a 224 px input, so a small cached thumbnail is plenty
private const val MAX_LABELS = 10
// Long side for text recognition: a full screenshot (2400 px tall) shrinks only a little, and small print on a photographed
// receipt keeps the 16 px per character ML Kit needs. A thumbnail would blur both.
private const val TEXT_PX = 2048
// Prefs key holding the id of the photo being decoded in this process. A native crash in the decoder kills the app before
// it can catch anything, so the next pass finds the id still there and skips that photo's text instead of crashing again.
private const val DECODING = "decoding"

/** How far the current indexing pass has got. (0, 0) when there's nothing to do. */
data class IndexProgress(val done: Int, val total: Int)

/** A MediaStore item as the indexer sees it. */
data class MediaStamp(val id: Long, val isVideo: Boolean, val modified: Long, val size: Long, val screenshot: Boolean = false)

class IndexPlan(val analyse: List<MediaStamp>, val remove: List<Long>)

/**
 * Which items need analysing (new, edited, analysed by an older version, or not yet located now that we may)
 * and which index rows belong to media that's gone. Keeps [found]'s order.
 */
fun planIndex(found: List<MediaStamp>, indexed: List<IndexStamp>, version: Int, canLocate: Boolean): IndexPlan {
    val known = indexed.associateBy { it.id }
    val analyse = found.filter { m ->
        val row = known[m.id]
        row == null || row.modified != m.modified || row.size != m.size || row.version != version || (canLocate && !row.located)
    }
    val present = found.mapTo(HashSet(found.size)) { it.id }
    return IndexPlan(analyse, indexed.filter { it.id !in present }.map { it.id })
}

/**
 * On-device index of what's in each photo (ML Kit labels, a category), where it was taken (town from GPS EXIF) and the
 * text in screenshots and documents. Nothing leaves the phone.
 * Read side for Explore and search: [categories], [places], [mediaIdsFor], [progress], [labels] and the ids lookups.
 */
object SmartIndex {
    private lateinit var app: Context
    private val dao by lazy { Room.databaseBuilder(app, IndexDb::class.java, "index.db")
            .fallbackToDestructiveMigration(true) // a cache rebuilt from the photos, so dropping it on a schema change is safe
            .build()
            .dao() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One background-priority thread for thumbnails, ML Kit and EXIF, so the UI thread always wins the CPU.
    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "smart-index")
    }
    private val background = thread.asCoroutineDispatcher()

    // Its own small file: it's written twice per decode, synchronously, so it must survive a crash right after
    private val marks by lazy { app.getSharedPreferences("smart-index", Context.MODE_PRIVATE) }

    private val _progress = MutableStateFlow(IndexProgress(0, 0))
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    /**
     * Call once from Application.onCreate. Indexing starts after the gallery's first load (so only once photo access
     * is granted) and runs again whenever the photos change. Unique work with KEEP: a running pass is never doubled.
     */
    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            Gallery.media.filterNotNull().collect {
                val request = OneTimeWorkRequestBuilder<IndexWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork("smart-index", ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    /** Things in the photos, biggest first. Updates live while indexing. */
    fun categories(): Flow<List<CategoryCount>> = dao.categories()

    /** Towns photos were taken in, most photos first. */
    fun places(): Flow<List<PlaceCount>> = dao.places()

    fun mediaIdsFor(category: Category): Flow<Set<Long>> = dao.ids(category).map { it.toSet() }

    fun mediaIdsFor(place: Place): Flow<Set<Long>> = dao.ids(place.city, place.admin1, place.countryCode).map { it.toSet() }

    /** Every ML Kit label some photo has, for search to recognise. */
    fun labels(): Flow<List<String>> = dao.labels()

    // Blocking lookups for search: call them off the main thread.

    fun idsIn(category: Category): Set<Long> = dao.idsIn(category).toSet()

    fun idsWith(label: String): Set<Long> = dao.idsWith(label).toSet()

    /** Photos taken in a town, state or country of this name. */
    fun idsAt(placeName: String): Set<Long> = dao.idsAt(placeName).toSet()

    /** Photos with a word starting with [prefix] in them. [prefix] must be letters and digits only (no FTS syntax). */
    fun idsWithText(prefix: String): Set<Long> = dao.idsWithText("$prefix*").toSet()

    /** One indexing pass: drops rows of deleted media, then analyses new and changed items in batches, newest first. */
    internal suspend fun run() = withContext(background) {
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext
        val canLocate = has(ACCESS_MEDIA_LOCATION)
        // With "Select photos" access MediaStore lists only the picked ones: never treat the rest as deleted
        val partial = !has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO)
        // 64k towns: parsed only if some photo in this pass has GPS, and dropped when the pass ends.
        val places = lazy { app.assets.open("geonames/cities.tsv").bufferedReader().useLines { Places.parse(it) } }
        var analysed = 0
        var failed = 0
        var read = 0
        val started = SystemClock.elapsedRealtime()
        val crashed = marks.getLong(DECODING, 0L)
        if (crashed != 0L) CrashLog.note("smart index: a photo crashed the decoder last time, its text is skipped")
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setExecutor(thread).build())
        val reader = TextRecognition.getClient(TextRecognizerOptions.Builder().setExecutor(thread).build())
        try {
            do {
                // Never act on a failed or empty query: either would wipe the index.
                val found = query()?.takeIf { it.isNotEmpty() } ?: return@withContext
                val plan = planIndex(found, dao.stamps(), INDEX_VERSION, canLocate)
                if (!partial) plan.remove.chunked(500).forEach { dao.remove(it) }
                _progress.value = IndexProgress(0, plan.analyse.size)
                for (batch in plan.analyse.chunked(BATCH)) {
                    val results = batch.map { item ->
                        try {
                            analyse(item, labeler, reader, canLocate, places, readText = item.id != crashed)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Out of memory on a huge photo, say: that photo is skipped, not the rest of the pass
                            CrashLog.note("smart index: skipped a photo: $e")
                            Analysis(unreadable(item), emptyList(), null)
                        }
                    }
                    dao.save(results.map { it.item }, results.flatMap { it.labels }, results.mapNotNull { it.text })
                    failed += results.count { it.item.category == null }
                    read += results.count { it.text != null }
                    _progress.update { it.copy(done = it.done + batch.size) }
                }
                analysed += plan.analyse.size
            } while (plan.analyse.isNotEmpty()) // photos taken during the pass are picked up before it ends
        } finally {
            labeler.close()
            reader.close()
        }
        if (analysed > 0) {
            val ms = (SystemClock.elapsedRealtime() - started) / analysed
            CrashLog.note("smart index: $analysed items, $ms ms each, $failed unreadable, $read with text")
        }
    }

    private fun has(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Null if MediaStore didn't answer. */
    private fun query(): List<MediaStamp>? {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_MODIFIED, FileColumns.SIZE, FileColumns.RELATIVE_PATH,
            FileColumns.DISPLAY_NAME,
        )
        val selection = PHOTOS_AND_VIDEOS
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return app.contentResolver.query(uri, projection, selection, null, "${FileColumns._ID} DESC")?.use { c ->
            List(c.count) {
                c.moveToNext()
                val isVideo = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
                MediaStamp(c.getLong(0), isVideo, c.getLong(2), c.getLong(3), !isVideo && isScreenshot(c.getString(4).orEmpty(), c.getString(5).orEmpty()))
            }
        }
    }

    private class Analysis(val item: IndexedItem, val labels: List<ItemLabel>, val text: ItemText?)

    // Saved with no category, like a corrupt file, so it's only tried again once the file changes
    private fun unreadable(item: MediaStamp) =
        IndexedItem(item.id, item.modified, item.size, INDEX_VERSION, category = null, lat = null, lng = null, located = true, place = null)

    // Videos are labelled from their thumbnail frame. Their GPS (MP4 metadata, not EXIF) and text aren't read yet.
    private suspend fun analyse(
        item: MediaStamp,
        labeler: ImageLabeler,
        reader: TextRecognizer,
        canLocate: Boolean,
        places: Lazy<Places>,
        readText: Boolean,
    ): Analysis {
        val uri = mediaUri(item.id, item.isVideo)
        val thumb = try {
            app.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
        } catch (e: Exception) {
            null // corrupt or vanished file: still saved (category null) so it isn't retried every pass
        }
        val labels = thumb?.let { labeler.process(InputImage.fromBitmap(it, 0)).await() }
            ?.sortedByDescending { it.confidence }?.take(MAX_LABELS)
            ?.map { it.text to it.confidence }
        val category = if (thumb != null && labels != null) categoryOf(labels, brightnessOf(thumb)) else null
        thumb?.recycle()
        val gps = if (canLocate && !item.isVideo) latLng(uri) else null
        val row = IndexedItem(
            id = item.id,
            modified = item.modified,
            size = item.size,
            version = INDEX_VERSION,
            category = category,
            lat = gps?.get(0),
            lng = gps?.get(1),
            located = canLocate || item.isVideo,
            place = gps?.let { places.value.nearest(it[0], it[1]) },
        )
        val text = if (readText && labels != null && !item.isVideo && mayHaveText(labels, item.screenshot)) textIn(item.id, uri, reader) else null
        return Analysis(
            row,
            labels.orEmpty().map { (label, confidence) -> ItemLabel(item.id, label, confidence) },
            text?.let { ItemText(item.id, it) },
        )
    }

    // ML Kit's Task as a suspend call; null when it fails.
    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { c ->
        addOnCompleteListener(thread) { c.resume(if (it.isSuccessful) it.result else null) }
    }

    /**
     * Text in the photo, decoded from the original at up to [TEXT_PX]. Null when it has none or can't be read.
     * The only full decode in this process (thumbnails come from MediaProvider), so it's the one [DECODING] guards.
     */
    private suspend fun textIn(id: Long, uri: Uri, reader: TextRecognizer): String? {
        marks.edit(commit = true) { putLong(DECODING, id) }
        try {
            val bitmap = decode(uri) ?: return null
            return try {
                // A failed task comes back as null; this catches an image ML Kit rejects outright
                runCatching { reader.process(InputImage.fromBitmap(bitmap, 0)) }.getOrNull()?.await()?.text?.takeIf { it.isNotBlank() }
            } finally {
                bitmap.recycle()
            }
        } finally {
            marks.edit(commit = true) { remove(DECODING) }
        }
    }

    // Same normalisation as Faces.decode: software sRGB ARGB_8888, which is what ML Kit reads
    private fun decode(uri: Uri): Bitmap? = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val scale = TEXT_PX.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        // 10-bit HDR photos decode to other configs
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    } catch (e: Exception) {
        null
    }

    private fun brightnessOf(bitmap: Bitmap): Float {
        val tiny = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(16 * 16).also { tiny.getPixels(it, 0, 16, 0, 0, 16, 16) }
        tiny.recycle()
        return brightnessOf(pixels)
    }

    // Android hides EXIF location unless the app holds ACCESS_MEDIA_LOCATION and asks for the original file. Read-only.
    private fun latLng(uri: Uri): DoubleArray? = try {
        app.contentResolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r")?.use { ExifInterface(it.fileDescriptor).latLong }
            ?.takeUnless { it[0] == 0.0 && it[1] == 0.0 } // some cameras write 0,0 when they had no fix
    } catch (e: Exception) {
        null
    }
}

/** Runs [SmartIndex.run] under WorkManager: resumes after process death, waits while the battery is low. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SmartIndex.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped by WorkManager (battery low): finished batches are saved, the rest runs next time
    } catch (e: Exception) {
        CrashLog.note("smart index failed: $e")
        Result.failure()
    }
}
