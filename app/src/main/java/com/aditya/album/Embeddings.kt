package com.aditya.album

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
import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bump when the vectors change (another model, other preprocessing), so every photo is encoded again. */
private const val DESCRIBE_VERSION = 1
private const val DIM = 512 // the model's embedding size
private const val INPUT = 384 // the image encoder's square input, in pixels
private const val THUMB_PX = 512 // MediaProvider's cached thumbnail, for videos and photos that can't be decoded
// Prefs key holding the id of the photo being decoded in this process. A native crash in the decoder kills the app before
// it can catch anything, so the next pass finds the id still there and encodes that photo from its thumbnail instead.
private const val DECODING = "decoding"
private const val BATCH = 16 // photos per database write; also how often progress moves
// Threads for the image model. On this Mac (LiteRT 1.3) one photo takes 280 ms on 1 thread, 150 ms on 2; a phone core
// is about 3 times slower, so a 20,000 photo library takes about 2 to 3 hours on 2, once. Calibration knob: more threads
// finish sooner but warm the phone, alongside the labels and faces passes.
private const val THREADS = 2
private const val WORK = "describe-index"
private const val JOB = "Describe-it search" // on the Organising card
private const val RECENT = 8 // query vectors kept, so retyping or going back doesn't run the model again
private const val IDLE_MS = 120_000L // the text model and the vectors leave memory this long after the last search

/**
 * Search's relevance floor, picked from tools/describe_convert.py's floor study (70 photos, 28 short queries labelled
 * by hand): with this model a photo's true match scores 0.23 to 0.34 and unrelated photos reach 0.29, so no single
 * cut-off separates them. A photo counts from [FLOOR] up when it's also within [WINDOW] of the best photo: that kept
 * every labelled match and let in about 2 unrelated photos in 70 per query. For something no photo shows it still
 * lets in about 4 in 70 (the model can't tell "no dog here" from a weak match), so describe-it results belong after
 * exact matches.
 */
internal const val FLOOR = 0.23f
internal const val WINDOW = 0.06f

sealed interface DescribeState {
    data object NotInstalled : DescribeState
    data class Downloading(val fraction: Float) : DescribeState
    data class Indexing(val done: Int, val total: Int) : DescribeState
    data object Ready : DescribeState
    data class Failed(val message: String) : DescribeState
}

/**
 * Describe-it search: find photos by describing them ("dog on a beach at sunset"). Meta's Perception Encoder turns each
 * photo, and then the words typed, into vectors in the same space; search returns the photos whose vectors point the
 * same way. The model is a one-time download ([ModelDownload]); everything else runs on the phone, and nothing leaves it.
 * The index (embeddings.db) is a cache that can be rebuilt any time.
 */
object Embeddings {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db by lazy {
        Room.databaseBuilder(app, EmbeddingDb::class.java, "embeddings.db")
            .fallbackToDestructiveMigration(true) // a cache rebuilt from the photos, so dropping it on a schema change is safe
            .build()
    }
    private val dao by lazy { db.dao() }

    // One background-priority thread for thumbnails and the image model. The model's own threads start from it and get
    // its priority too, so the UI thread always wins the CPU.
    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "describe")
    }
    private val background = thread.asCoroutineDispatcher()

    // Its own small file: it's written twice per decode, synchronously, so it must survive a crash right after
    private val marks by lazy { app.getSharedPreferences("describe", Context.MODE_PRIVATE) }

    // Downloads and removals, one at a time in the order tapped. Their work blocks without suspending, so a removal
    // can't interleave with a download that follows it.
    private val installer = Dispatchers.IO.limitedParallelism(1)
    private var downloading: Job? = null

    private val _state = MutableStateFlow<DescribeState>(DescribeState.NotInstalled)

    /** What describe-it search is doing, for the Settings row. */
    val state: StateFlow<DescribeState> = _state.asStateFlow()

    // Search side, guarded by [searching]: loaded on the first search, released IDLE_MS after the last one
    private val searching = Mutex()
    private var text: TextEncoder? = null
    private var loaded: VectorIndex? = null
    private var loadedAt = -1
    private var idle: Job? = null
    private val recent = object : LinkedHashMap<String, FloatArray>(RECENT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) = size > RECENT
    }
    private val changes = AtomicInteger() // bumped whenever saved vectors change, so search loads them again

    /**
     * Call once from Application.onCreate. Once the model is installed, photos are encoded after the gallery's first
     * load and again whenever they change. Unique work with KEEP: a running pass is never doubled.
     */
    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            if (ModelDownload.installed(app)) _state.compareAndSet(DescribeState.NotInstalled, DescribeState.Ready)
            Gallery.media.filterNotNull().collect { if (installed()) index() }
        }
    }

    /** Downloads and installs the model (only on the user's tap), then encodes the photos. Progress is in [state]. */
    fun download(context: Context) {
        app = context.applicationContext
        if (downloading?.isActive == true) return
        CrashLog.note("describe: download")
        _state.value = DescribeState.Downloading(0f)
        downloading = scope.launch(installer) {
            try {
                ModelDownload.download(app) { fraction ->
                    ensureActive() // Cancel or Remove stops it within one buffer
                    _state.value = DescribeState.Downloading(fraction)
                }
                CrashLog.note("describe: installed")
                _state.value = DescribeState.Ready
                index()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.note("describe: download failed: $e")
                _state.value = DescribeState.Failed(if (e is DownloadError) e.message.orEmpty() else "Couldn't install describe-it search. Try again.")
            }
        }
    }

    /** Stops any download or encoding, then deletes the model, partial downloads and the index. */
    fun remove(context: Context) {
        app = context.applicationContext
        CrashLog.note("describe: remove")
        _state.value = DescribeState.NotInstalled
        downloading?.cancel()
        WorkManager.getInstance(app).cancelUniqueWork(WORK)
        scope.launch(installer) {
            ModelDownload.remove(app)
            dao.clear()
            db.openHelper.writableDatabase.execSQL("VACUUM") // gives the space back instead of keeping it for later rows
            changes.incrementAndGet()
        }
        scope.launch { searching.withLock { release() } }
    }

    /**
     * Photos and videos that look like [text], best first, as (MediaStore id, cosine similarity) above the relevance
     * floor ([FLOOR], [WINDOW]), at most [limit]. Searches what's encoded so far, so results grow while a first pass
     * runs. Null when describe-it search isn't installed or can't run. Call from any thread.
     */
    suspend fun search(text: String, limit: Int = 500): List<Pair<Long, Float>>? {
        if (!::app.isInitialized || !installed()) return null
        val query = text.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            searching.withLock {
                try {
                    val vector = recent[query] ?: textEncoder().encode(query).also { recent[query] = it }
                    val photos = vectors()
                    idle?.cancel()
                    idle = scope.launch {
                        delay(IDLE_MS)
                        searching.withLock { release() }
                    }
                    photos.rank(vector, limit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CrashLog.note("describe: search failed: $e")
                    null
                }
            }
        }
    }

    /**
     * The vectors, at unit length, of the photos in [ids] encoded so far, for grouping pets (see groupPets). Empty without
     * describe-it search: Remove deletes them all. Read from the database, not [state], which is only known a moment
     * after launch.
     */
    suspend fun vectorsOf(ids: Collection<Long>): Map<Long, FloatArray> {
        if (!::app.isInitialized) return emptyMap()
        return ids.chunked(500).flatMap { dao.vectorsIn(it) } // under SQLite's 999-variable limit
            .associate { r -> r.id to unit(FloatArray(r.vector.size) { r.vector[it] * r.scale }) }
    }

    private fun installed() = _state.value.let { it is DescribeState.Ready || it is DescribeState.Indexing }

    private fun index() {
        val request = OneTimeWorkRequestBuilder<DescribeWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** The model can't be loaded (damaged, say): Settings offers Try again, which checks the files and repairs them. */
    private fun broken(e: Throwable) {
        CrashLog.note("describe: model failed to load: $e")
        _state.update { if (it is DescribeState.Ready || it is DescribeState.Indexing) DescribeState.Failed("Describe-it search couldn't start. Try again to repair it.") else it }
    }

    // Call holding [searching]
    private fun textEncoder(): TextEncoder {
        text?.let { return it }
        val encoder = try {
            val tokenizer = ClipTokenizer(app.assets.open(MERGES_ASSET).bufferedReader().use { it.readLines() })
            TextEncoder(File(ModelDownload.dir(app), ModelDownload.TEXT.name), tokenizer)
        } catch (e: Exception) {
            broken(e)
            throw e
        }
        text = encoder
        return encoder
    }

    // Call holding [searching]. Reloaded only after the index changed: 20,000 photos are 10 MB.
    private suspend fun vectors(): VectorIndex {
        val at = changes.get()
        loaded?.takeIf { loadedAt == at }?.let { return it }
        val rows = dao.vectors()
        val values = ByteArray(rows.size * DIM)
        rows.forEachIndexed { i, row -> row.vector.copyInto(values, i * DIM) }
        return VectorIndex(LongArray(rows.size) { rows[it].id }, values, FloatArray(rows.size) { rows[it].scale }).also {
            loaded = it
            loadedAt = at
        }
    }

    // Call holding [searching]
    private fun release() {
        text?.close()
        text = null
        loaded = null
    }

    /** One encoding pass: drops vectors of deleted media, then encodes new and changed items in batches, newest first. */
    internal suspend fun run(onLongPass: suspend () -> Unit = {}) = withContext(background) {
        if (!installed()) return@withContext
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext
        // With "Select photos" access MediaStore lists only the picked ones: never treat the rest as deleted
        val partial = !has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO)
        val encoder = try {
            ImageEncoder(File(ModelDownload.dir(app), ModelDownload.IMAGE.name))
        } catch (e: Exception) {
            broken(e)
            return@withContext
        }
        var encoded = 0
        var unreadable = 0
        val started = SystemClock.elapsedRealtime()
        val crashed = marks.getLong(DECODING, 0L)
        if (crashed != 0L) CrashLog.note("describe: a photo crashed the decoder last time, its thumbnail is used")
        try {
            do {
                // Never act on a failed or empty query: either would wipe the index.
                val found = query()?.takeIf { it.isNotEmpty() } ?: break
                val plan = planIndex(found, dao.stamps(), DESCRIBE_VERSION, canLocate = false)
                if (!partial && plan.remove.isNotEmpty()) {
                    plan.remove.chunked(500).forEach { dao.remove(it) } // under SQLite's 999-variable limit
                    changes.incrementAndGet()
                }
                // Counted over the whole library, so a pass that resumes shows where it is, not a fresh start from 0
                var done = found.size - plan.analyse.size
                progress(done, found.size)
                for ((i, batch) in plan.analyse.chunked(BATCH).withIndex()) {
                    if (plan.analyse.size - i * BATCH >= LONG_PASS) onLongPass()
                    val rows = batch.map { item ->
                        ensureActive() // Remove, or the battery running low, stops it within a photo
                        encode(encoder, item, decode = item.id != crashed)
                    }
                    dao.save(rows)
                    changes.incrementAndGet()
                    unreadable += rows.count { it.vector == null }
                    done += batch.size
                    progress(done, found.size)
                }
                encoded += plan.analyse.size
            } while (plan.analyse.isNotEmpty()) // photos taken during the pass are picked up before it ends
        } finally {
            encoder.close()
            IndexStatus.report(JOB, 0, 0) // finished or stopped: off the card
            _state.update { if (it is DescribeState.Indexing) DescribeState.Ready else it }
        }
        if (encoded > 0) {
            val ms = (SystemClock.elapsedRealtime() - started) / encoded
            CrashLog.note("describe: $encoded items, $ms ms each, $unreadable unreadable")
        }
    }

    private fun progress(done: Int, total: Int) {
        IndexStatus.report(JOB, done, total)
        _state.update {
            if (it !is DescribeState.Ready && it !is DescribeState.Indexing) it
            else if (done < total) DescribeState.Indexing(done, total) else DescribeState.Ready
        }
    }

    /**
     * A photo is decoded from the original. MediaProvider's thumbnails are small JPEGs, and on the check photos their
     * artefacts moved the embedding to cosine 0.95 of what Meta's own preprocessing gives (0.997 decoded). Videos, and a
     * photo that can't be decoded or crashed the decoder before, use the thumbnail, made in MediaProvider's process.
     */
    private fun encode(encoder: ImageEncoder, item: MediaStamp, decode: Boolean): PhotoVector {
        val uri = mediaUri(item.id, item.isVideo)
        val vector = try {
            val picture = (if (decode && !item.isVideo) original(item.id, uri) else null)
                ?: app.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
            try {
                encoder.encode(picture)
            } finally {
                picture.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null // corrupt or vanished file, or out of memory: saved without a vector so it isn't retried every pass
        }
        val (bytes, scale) = vector?.let(::quantize) ?: (null to 0f)
        return PhotoVector(item.id, item.modified, item.size, DESCRIBE_VERSION, bytes, scale)
    }

    /**
     * The photo squashed to twice the model's input (halving it then averages pixels, close to Meta's resize), upright,
     * in sRGB. Null when it can't be decoded. The decode runs in this process, so it's what [DECODING] guards.
     */
    private fun original(id: Long, uri: Uri): Bitmap? {
        marks.edit(commit = true) { putLong(DECODING, id) }
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setTargetSize(2 * INPUT, 2 * INPUT)
            }
        } catch (e: Exception) {
            return null
        } finally {
            marks.edit(commit = true) { remove(DECODING) }
        }
    }

    private fun has(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Photos and videos, newest first. Null if MediaStore didn't answer. */
    private fun query(): List<MediaStamp>? {
        val projection = arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_MODIFIED, FileColumns.SIZE)
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return app.contentResolver.query(uri, projection, PHOTOS_AND_VIDEOS, null, "${FileColumns._ID} DESC")?.use { c ->
            List(c.count) {
                c.moveToNext()
                MediaStamp(c.getLong(0), c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO, c.getLong(2), c.getLong(3))
            }
        }
    }
}

/** Runs [Embeddings.run] under WorkManager: resumes after process death, waits while the battery is low. */
class DescribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val longPass = LongPassGate(this)

    override suspend fun doWork(): Result = try {
        Embeddings.run(onLongPass = longPass::tick)
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped by WorkManager (battery low, or Remove): finished batches are saved, the rest runs next time
    } catch (e: Exception) {
        CrashLog.note("describe: index failed: $e")
        Result.failure()
    }
}

/** The image half of the model: a photo to its unit-length vector, on the CPU. Not thread-safe. */
private class ImageEncoder(file: File) : Closeable {
    private val model = Interpreter(file, Interpreter.Options().setNumThreads(THREADS))
    private val input = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(INPUT * INPUT)
    private val output = Array(1) { FloatArray(DIM) }

    fun encode(photo: Bitmap): FloatArray {
        // Squashed to a square, as Meta's preprocessing does: the whole photo, nothing cropped off. Bilinear.
        val square = Bitmap.createScaledBitmap(photo, INPUT, INPUT, true)
        square.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (square !== photo) square.recycle()
        input.rewind()
        for (p in pixels) { // RGB 0 to 255; the model normalises itself
            input.putFloat((p shr 16 and 0xff).toFloat())
            input.putFloat((p shr 8 and 0xff).toFloat())
            input.putFloat((p and 0xff).toFloat())
        }
        input.rewind()
        model.run(input, output)
        return output[0].copyOf()
    }

    override fun close() = model.close()
}

/** The text half: words to a unit-length vector in the same space as the photos'. Not thread-safe. */
private class TextEncoder(file: File, private val tokenizer: ClipTokenizer) : Closeable {
    private val model = Interpreter(file, Interpreter.Options().setNumThreads(1)) // 32 tokens: more threads cost more than they save
    private val ids = Array(1) { IntArray(TEXT_TOKENS) }
    private val output = Array(1) { FloatArray(DIM) }

    fun encode(text: String): FloatArray {
        tokenizer.encode(text).copyInto(ids[0])
        model.run(ids, output)
        return output[0].copyOf()
    }

    override fun close() = model.close()
}

/** Every encoded photo's vector in one array: photo i's components are values[i * dim until (i + 1) * dim]. */
internal class VectorIndex(val ids: LongArray, val values: ByteArray, val scales: FloatArray, val dim: Int = DIM)

/**
 * [v] as int8: each component times 127 over the largest one, rounded, and the scale that turns them back. For a unit
 * vector the error in a cosine is under 0.005, far below the gap between a match and not.
 */
internal fun quantize(v: FloatArray): Pair<ByteArray, Float> {
    val max = v.maxOf { abs(it) }
    if (max == 0f) return ByteArray(v.size) to 0f
    return ByteArray(v.size) { (v[it] * 127 / max).roundToInt().toByte() } to max / 127
}

/** Photos by cosine similarity to [query] (unit length), best first: those above the relevance floor, at most [limit]. */
internal fun VectorIndex.rank(query: FloatArray, limit: Int, floor: Float = FLOOR, window: Float = WINDOW): List<Pair<Long, Float>> {
    if (ids.isEmpty() || limit <= 0) return emptyList()
    val (q, qScale) = quantize(query)
    // ponytail: a full scan with int8 dot products, about 10 million multiply-adds for 20,000 photos (tens of ms). An
    // approximate index would only pay off far beyond a phone's library.
    val scores = FloatArray(ids.size) { i ->
        var dot = 0
        val base = i * dim
        for (k in 0 until dim) dot += values[base + k] * q[k]
        dot * scales[i] * qScale
    }
    val cut = maxOf(floor, scores.max() - window)
    return scores.indices.filter { scores[it] >= cut }.sortedByDescending { scores[it] }.take(limit).map { ids[it] to scores[it] }
}

/**
 * Describe-it search in Settings: what it is, its size, and Download, the progress, or Remove. Removing asks first: it
 * costs a 97 MB download and hours of encoding to get back.
 */
@Composable
fun DescribeSearchRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by Embeddings.state.collectAsState()
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val size = remember { Formatter.formatShortFileSize(context, ModelDownload.TOTAL) }
    var confirm by remember { mutableStateOf(false) }
    val status = when (val s = state) {
        DescribeState.NotInstalled -> "One-time download of $size"
        is DescribeState.Downloading -> "Downloading · ${(s.fraction * 100).toInt()}% of $size"
        is DescribeState.Indexing -> "Getting your photos ready · ${"%,d".format(s.done)} of ${"%,d".format(s.total)}"
        DescribeState.Ready -> "Ready · $size on this phone"
        is DescribeState.Failed -> s.message
    }
    Column(modifier.fillMaxWidth().padding(vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.Top) {
            Icon(Sym.Search, null, tint = c.inkMuted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Describe-it search", style = type.body, color = c.ink)
                Text("Find photos by describing them, like \"kids on a beach at sunset\". Runs on this phone; nothing is uploaded.", style = type.caption, color = c.inkMuted)
                Text(status, Modifier.padding(top = Space.s1), style = if (state is DescribeState.Failed) type.caption else type.meta, color = c.inkMuted)
            }
        }
        // Progress is information, not decoration, so it moves even with Remove animations on
        when (val s = state) {
            is DescribeState.Downloading -> ProgressLine(s.fraction)
            is DescribeState.Indexing -> ProgressLine(s.done.toFloat() / s.total)
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            when (state) {
                DescribeState.NotInstalled -> AlbumButton("Download", { Embeddings.download(context) }, variant = ButtonVariant.Primary, icon = Sym.Download)
                is DescribeState.Failed -> {
                    AlbumButton("Try again", { Embeddings.download(context) }, variant = ButtonVariant.Primary, icon = Sym.Refresh)
                    AlbumButton("Remove", { Embeddings.remove(context) }, variant = ButtonVariant.Quiet)
                }
                // Quiet labels line up with the margin, as Settings' other quiet buttons do
                is DescribeState.Downloading -> AlbumButton("Cancel", { Embeddings.remove(context) }, Modifier.offset(x = -Space.s3), variant = ButtonVariant.Quiet)
                else -> AlbumButton("Remove", { confirm = true }, Modifier.offset(x = -Space.s3), variant = ButtonVariant.Quiet)
            }
        }
    }
    if (confirm) {
        ConfirmBar(
            title = "Remove describe-it search?",
            body = "Frees $size. To use it again, download it and let Album read your photos again.",
            confirmLabel = "Remove",
            onConfirm = {
                confirm = false
                Embeddings.remove(context)
            },
            onCancel = { confirm = false },
        )
    }
}
