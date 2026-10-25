package com.aditya.album

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.Size
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bump when measuring changes (thumbnail size, hash, sharpness) so every photo is measured again. */
private const val CLEANUP_VERSION = 1
private const val BATCH = 32 // items per database transaction; also how often progress moves
// The grey image dHash and sharpness read: a cached MediaProvider thumbnail, never a full decode. Small on purpose, so
// only real blur shows; the slight softness of a phone photo disappears at this size.
private const val THUMB_PX = 256
private const val JOB = "Clean-up" // as IndexStatus shows it

// Tuning knobs, set conservatively: a missed suggestion costs nothing, a wrong one costs trust. Retune on a real library.
/** Two photos are near-duplicates when their dHashes differ in at most this many of the 64 bits... */
const val SIMILAR_BITS = 6
/** ...and they were taken at most this far apart: a burst, or a few tries at the same shot. */
const val SIMILAR_WINDOW_MS = 3 * 60 * 1000L
/** Below this Laplacian variance (on the [THUMB_PX] grey image) a photo with enough contrast reads as blurry. */
const val BLUR_SHARPNESS = 12f
/** Flat photos (fog, a plain wall, the night sky) have no edges to judge, so below this grey spread they are never "blurry". */
const val BLUR_MIN_CONTRAST = 20f
const val OLD_SCREENSHOT_MS = 30 * 24 * 60 * 60 * 1000L
const val BIG_VIDEO_BYTES = 100_000_000L // 100 MB the way Android shows sizes (1,000-based)

/**
 * Cleanup's categories, in chip order. [reason] is the Badge on a swipe card. [suggested] ones make up "N photos to
 * review"; the others are there to browse.
 */
enum class CleanupKind(val label: String, val reason: String, val sym: Sym, val suggested: Boolean) {
    Similar("Similar", "Similar", Sym.BurstMode, true),
    Duplicates("Duplicates", "Duplicate", Sym.ContentCopy, true),
    Blurry("Blurry", "Blurry", Sym.Contrast, true),
    Screenshots("Old screenshots", "Old screenshot", Sym.ScreenshotMonitor, true),
    WhatsApp("WhatsApp", "WhatsApp", Sym.Share, false),
    BigVideos("Big videos", "Big video", Sym.Videocam, false),
}

/** What cleanup reads of one item: a [Media] without its URI, so the rules run in plain unit tests. */
data class Shot(
    val id: Long,
    val isVideo: Boolean,
    val taken: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val bucket: String,
    val path: String,
    val name: String,
)

fun Media.toShot() = Shot(id, isVideo, taken, width, height, size, bucketName, path, name)

/** Photos that look alike, or copies of one file. [ids] start with [keep], the one Album suggests keeping. */
data class ShotGroup(val keep: Long, val ids: List<Long>)

/** What each category suggests. Groups for similar shots and duplicates; plain lists, newest first, for the rest. */
data class CleanupResult(
    val similar: List<ShotGroup>,
    val duplicates: List<ShotGroup>,
    val blurry: List<Long>,
    val screenshots: List<Long>,
    val whatsApp: List<Long>,
    val bigVideos: List<Long>, // biggest first
) {
    /** What [kind] suggests moving to Trash: every item, except the one to keep in each group. */
    fun candidates(kind: CleanupKind): List<Long> = when (kind) {
        CleanupKind.Similar -> similar.flatMap { it.ids.drop(1) }
        CleanupKind.Duplicates -> duplicates.flatMap { it.ids.drop(1) }
        CleanupKind.Blurry -> blurry
        CleanupKind.Screenshots -> screenshots
        CleanupKind.WhatsApp -> whatsApp
        CleanupKind.BigVideos -> bigVideos
    }
}

data class CleanupCount(val count: Int, val bytes: Long)

/** Per category, how many items it suggests and the space they'd free. [toReview] counts each suggested item once. */
data class CleanupSummary(val kinds: Map<CleanupKind, CleanupCount>, val toReview: Int, val reviewBytes: Long)

// ---------- Rules: pure, so they're unit tested ----------

/**
 * Sorts the Library into cleanup categories. [rows] are what [Cleanup] measured, by id; items not measured yet are only
 * left out of the categories that need a measurement. [now] is epoch millis.
 */
fun cleanupOf(shots: List<Shot>, rows: Map<Long, CleanupRow>, now: Long): CleanupResult {
    val duplicates = duplicateGroups(shots) { rows[it.id]?.hash }
    val copies = duplicates.flatMapTo(HashSet()) { it.ids.drop(1) }
    // Screenshots of one app look alike at 9 by 8 pixels and are always "sharp", so they get their own category instead
    val photos = shots.filter { !it.isVideo && !isScreenshot(it.path, it.name) }
    return CleanupResult(
        similar = similarGroups(photos.filter { it.id !in copies }, { rows[it.id]?.dhash }, { rows[it.id]?.sharpness ?: 0f }),
        duplicates = duplicates,
        blurry = photos.filter { s -> rows[s.id]?.let { isBlurry(it.sharpness, it.contrast) } == true }.map { it.id },
        screenshots = shots.filter { !it.isVideo && isScreenshot(it.path, it.name) && now - it.taken > OLD_SCREENSHOT_MS }.map { it.id },
        whatsApp = shots.filter { storageKind(it.isVideo, it.path, it.bucket, it.name) == StorageKind.WhatsApp }.map { it.id },
        bigVideos = shots.filter { it.isVideo && it.size > BIG_VIDEO_BYTES }.sortedByDescending { it.size }.map { it.id },
    )
}

/** Counts and bytes per category, and the suggested items counted once (a blurry similar shot is one photo to review). */
fun summaryOf(result: CleanupResult, sizeOf: (Long) -> Long): CleanupSummary {
    val kinds = CleanupKind.entries.associateWith { kind ->
        val ids = result.candidates(kind)
        CleanupCount(ids.size, ids.sumOf(sizeOf))
    }
    val review = CleanupKind.entries.filter { it.suggested }.flatMapTo(HashSet()) { result.candidates(it) }
    return CleanupSummary(kinds, review.size, review.sumOf(sizeOf))
}

/** Byte-for-byte copies: same size and same content hash. The first one saved (lowest id, usually the original) is kept. */
fun duplicateGroups(shots: List<Shot>, hash: (Shot) -> String?): List<ShotGroup> =
    shots.filter { it.size > 0 && !hash(it).isNullOrEmpty() }
        .groupBy { it.size to hash(it) }.values
        .filter { it.size > 1 }
        .sortedByDescending { it[0].size * (it.size - 1) } // the most space back first
        .map { copies -> copies.map { it.id }.sorted().let { ShotGroup(it.first(), it) } }

/**
 * Groups of near-duplicate photos: each taken within [SIMILAR_WINDOW_MS] of another in its group, in the same orientation,
 * with dHashes at most [SIMILAR_BITS] apart. Photos with no [dhash] (not measured yet, unreadable) are left out. Each group
 * is its [bestShot] first, then newest first; groups newest first.
 */
fun similarGroups(photos: List<Shot>, dhash: (Shot) -> Long?, sharpness: (Shot) -> Float): List<ShotGroup> {
    val items = photos.filter { dhash(it) != null }.sortedBy { it.taken }
    val hashes = LongArray(items.size) { dhash(items[it])!! }
    val parent = IntArray(items.size) { it }
    fun root(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        return r
    }
    // ponytail: compares every pair inside the time window, O(n·k). A 300-shot burst is 45k cheap comparisons; fine.
    for (i in items.indices) {
        var j = i + 1
        while (j < items.size && items[j].taken - items[i].taken <= SIMILAR_WINDOW_MS) {
            if (landscape(items[i]) == landscape(items[j]) && hamming(hashes[i], hashes[j]) <= SIMILAR_BITS) parent[root(j)] = root(i)
            j++
        }
    }
    return items.indices.groupBy(::root).values
        .filter { it.size > 1 }
        .map { group -> group.map(items::get) }
        .sortedByDescending { group -> group.maxOf { it.taken } }
        .map { group ->
            val best = bestShot(group, sharpness)
            ShotGroup(best.id, listOf(best.id) + group.filter { it !== best }.sortedByDescending { it.taken }.map { it.id })
        }
}

private fun landscape(s: Shot) = s.width > s.height

/**
 * The shot to keep from near-duplicates: the sharpest, then the most pixels, then the newest. Sharpness is compared in steps
 * of about 15%, since the measure is rough, so a bigger or newer shot can still win over one that's barely sharper.
 */
fun bestShot(shots: List<Shot>, sharpness: (Shot) -> Float): Shot =
    shots.maxWith(compareBy<Shot>({ sharpStep(sharpness(it)) }, { it.width.toLong() * it.height }, { it.taken }, { it.id }))

private fun sharpStep(sharpness: Float) = (ln(1.0 + sharpness.coerceAtLeast(0f)) / ln(1.15)).toInt()

/** Conservative: only a photo with something to see (contrast) and almost no crisp edges at all. */
fun isBlurry(sharpness: Float?, contrast: Float?) =
    sharpness != null && contrast != null && contrast >= BLUR_MIN_CONTRAST && sharpness < BLUR_SHARPNESS

/** Grey levels (0 to 255) of ARGB [pixels], with the usual luma weights. */
fun greyOf(pixels: IntArray) = IntArray(pixels.size) {
    val p = pixels[it]
    (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
}

/**
 * 64-bit difference hash of a [w] by [h] grey image: shrunk (box average) to 9 by 8, one bit per pair of neighbours in a
 * row, set when the left one is brighter. It survives resizing, recompression and small exposure changes; a different
 * scene flips many bits.
 */
fun dHash(grey: IntArray, w: Int, h: Int): Long {
    val cells = DoubleArray(9 * 8)
    for (cy in 0 until 8) {
        val y0 = cy * h / 8
        val y1 = maxOf(y0 + 1, (cy + 1) * h / 8)
        for (cx in 0 until 9) {
            val x0 = cx * w / 9
            val x1 = maxOf(x0 + 1, (cx + 1) * w / 9)
            var sum = 0L
            for (y in y0 until y1) for (x in x0 until x1) sum += grey[y * w + x]
            cells[cy * 9 + cx] = sum.toDouble() / ((y1 - y0) * (x1 - x0))
        }
    }
    var bits = 0L
    for (cy in 0 until 8) for (cx in 0 until 8) {
        if (cells[cy * 9 + cx] > cells[cy * 9 + cx + 1]) bits = bits or (1L shl (cy * 8 + cx))
    }
    return bits
}

fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

/** Variance of the Laplacian: high where edges are crisp, near zero when the whole image is soft. */
fun sharpnessOf(grey: IntArray, w: Int, h: Int): Float {
    if (w < 3 || h < 3) return 0f
    var sum = 0.0
    var squares = 0.0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val l = (grey[i - 1] + grey[i + 1] + grey[i - w] + grey[i + w] - 4 * grey[i]).toDouble()
        sum += l
        squares += l * l
    }
    val n = (w - 2) * (h - 2)
    val mean = sum / n
    return (squares / n - mean * mean).toFloat()
}

/** Standard deviation of the grey levels: how much there is to see. */
fun contrastOf(grey: IntArray): Float {
    if (grey.isEmpty()) return 0f
    val mean = grey.average()
    return sqrt(grey.sumOf { (it - mean) * (it - mean) } / grey.size).toFloat()
}

/** SHA-256 of everything [input] holds, as hex. Streamed in 64 KB pieces, so a 4 GB video needs no more memory than a photo. */
fun contentHash(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Sizes two or more files share: only those files need a content hash. Size 0 means MediaStore doesn't know it. */
fun sharedSizes(sizes: List<Long>): Set<Long> = sizes.filter { it > 0 }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

/** Whether [row] still describes [item]: same file, measured by this version. */
fun CleanupRow.matches(item: MediaStamp, version: Int) = modified == item.modified && size == item.size && this.version == version

/** One file to measure: [look] reads its thumbnail (photos only), [hash] reads the whole file (it shares its size with another). */
class CleanupJob(val item: MediaStamp, val look: Boolean, val hash: Boolean)

class CleanupPlan(val work: List<CleanupJob>, val remove: List<Long>)

/** What to measure (new or edited files, and files that just gained a same-size twin) and which rows belong to media that's gone. */
fun planCleanup(found: List<MediaStamp>, rows: List<CleanupRow>, version: Int): CleanupPlan {
    val known = rows.associateBy { it.id }
    val shared = sharedSizes(found.map { it.size })
    val work = ArrayList<CleanupJob>()
    val remove = ArrayList<Long>()
    for (item in found) {
        val row = known[item.id]?.takeIf { it.matches(item, version) }
        val look = !item.isVideo && row == null
        val hash = item.size in shared && row?.hash == null
        when {
            look || hash -> work += CleanupJob(item, look, hash)
            row == null && item.id in known -> remove += item.id // an edited video that no longer needs a hash
        }
    }
    val present = found.mapTo(HashSet(found.size)) { it.id }
    rows.filter { it.id !in present }.mapTo(remove) { it.id }
    return CleanupPlan(work, remove)
}

// ---------- Detection ----------

/**
 * Finds what can go: exact duplicates, near-duplicates and bursts (with the best shot picked), blurry photos, old
 * screenshots, WhatsApp media and big videos. Each file is measured once (a thumbnail, and a content hash only when another
 * file has the same size) and kept in its own database, so later passes only measure what's new. Nothing leaves the phone
 * and nothing is deleted here: the cleanup screen asks.
 */
object Cleanup {
    private lateinit var app: Context
    private val dao by lazy {
        Room.databaseBuilder(app, CleanupDb::class.java, "cleanup.db")
            .fallbackToDestructiveMigration(true) // measurements rebuilt from the photos, so dropping them on a schema change is safe
            .build()
            .dao()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One background-priority thread for thumbnails and hashing, so the UI thread always wins the CPU
    private val background = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "cleanup")
    }.asCoroutineDispatcher()

    private val passes = MutableStateFlow(0) // goes up after each pass, so the categories are worked out again

    private val _result = MutableStateFlow<CleanupResult?>(null)
    private val _summary = MutableStateFlow<CleanupSummary?>(null)

    /** The categories and what's in them. null until the first look at the Library. Updates live. */
    val result: StateFlow<CleanupResult?> = _result.asStateFlow()

    /** Counts and bytes per category; For you shows [CleanupSummary.toReview]. null until the first look at the Library. */
    val summary: StateFlow<CleanupSummary?> = _summary.asStateFlow()

    /**
     * Call once from Application.onCreate. Measuring starts after the gallery's first load (so only once photo access is
     * granted) and runs again whenever the photos change. Unique work with KEEP: a running pass is never doubled.
     */
    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            Gallery.media.filterNotNull().collect {
                val request = OneTimeWorkRequestBuilder<CleanupWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(JOB, ExistingWorkPolicy.KEEP, request)
            }
        }
        // Categories come from the Library as it is now plus what's measured so far, so trashed photos leave at once
        scope.launch {
            combine(Gallery.media.filterNotNull(), passes) { media, _ -> media }.collectLatest { media ->
                val shots = media.map { it.toShot() }
                val sizes = shots.associate { it.id to it.size }
                val result = cleanupOf(shots, dao.all().associateBy { it.id }, System.currentTimeMillis())
                _result.value = result
                _summary.value = summaryOf(result) { sizes[it] ?: 0L }
            }
        }
    }

    /** One pass: drops rows of deleted media, then measures new and changed files in batches, newest first. */
    internal suspend fun run(onLongPass: suspend () -> Unit = {}) = withContext(background) {
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext
        // With "Select photos" access MediaStore lists only the picked ones: never treat the rest as deleted
        val partial = !has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO)
        var measured = 0
        val started = SystemClock.elapsedRealtime()
        try {
            do {
                // Never act on a failed or empty query: either would wipe the measurements
                val found = query()?.takeIf { it.isNotEmpty() } ?: return@withContext
                val rows = dao.all()
                val plan = planCleanup(found, rows, CLEANUP_VERSION)
                if (!partial) plan.remove.chunked(500).forEach { dao.remove(it) }
                val known = rows.associateBy { it.id }
                // Counted over the whole library, so a pass that resumes shows where it is, not a fresh start from 0
                var done = found.size - plan.work.size
                IndexStatus.report(JOB, done, found.size)
                for ((i, batch) in plan.work.chunked(BATCH).withIndex()) {
                    if (plan.work.size - i * BATCH >= LONG_PASS) onLongPass()
                    dao.save(batch.map { measure(it, known[it.item.id]) })
                    done += batch.size
                    IndexStatus.report(JOB, done, found.size)
                }
                measured += plan.work.size
                if (plan.work.isNotEmpty()) passes.update { it + 1 }
            } while (plan.work.isNotEmpty()) // photos taken during the pass are picked up before it ends
        } finally {
            IndexStatus.report(JOB, 0, 0)
        }
        if (measured > 0) CrashLog.note("cleanup: $measured items, ${(SystemClock.elapsedRealtime() - started) / measured} ms each")
    }

    private fun has(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Null if MediaStore didn't answer. */
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

    /**
     * Measures one file on top of what's still valid in [old]. A file that can't be read is saved with nothing measured
     * (and an empty hash), so it's tried again only once it changes, instead of on every pass.
     */
    private fun measure(job: CleanupJob, old: CleanupRow?): CleanupRow {
        val item = job.item
        val uri = mediaUri(item.id, item.isVideo)
        val base = old?.takeIf { it.matches(item, CLEANUP_VERSION) }
            ?: CleanupRow(item.id, item.modified, item.size, CLEANUP_VERSION, dhash = null, sharpness = null, contrast = null, hash = null)
        var row = base
        if (job.look) {
            row = try {
                look(uri, row)
            } catch (e: Throwable) {
                CrashLog.note("cleanup: skipped a thumbnail: $e") // corrupt file, or out of memory on a huge one
                row
            }
        }
        if (job.hash) {
            val hash = try {
                app.contentResolver.openInputStream(uri)?.use(::contentHash)
            } catch (e: Exception) {
                null // vanished or unreadable
            }
            row = row.copy(hash = hash ?: "")
        }
        return row
    }

    // Thumbnails come from MediaProvider's cache, decoded in software (ContentResolver.loadThumbnail does that), so getPixels works
    private fun look(uri: Uri, row: CleanupRow): CleanupRow {
        val thumb = app.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
        val scale = THUMB_PX.toFloat() / maxOf(thumb.width, thumb.height)
        val small = if (scale >= 1f) thumb else {
            Bitmap.createScaledBitmap(thumb, (thumb.width * scale).roundToInt().coerceAtLeast(1), (thumb.height * scale).roundToInt().coerceAtLeast(1), true)
                .also { thumb.recycle() }
        }
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        small.recycle()
        val grey = greyOf(pixels)
        return row.copy(dhash = dHash(grey, w, h), sharpness = sharpnessOf(grey, w, h), contrast = contrastOf(grey))
    }
}

/** Runs [Cleanup.run] under WorkManager: resumes after process death, waits while the battery is low. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val longPass = LongPassGate(this)

    override suspend fun doWork(): Result = try {
        Cleanup.run(onLongPass = longPass::tick)
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped by WorkManager (battery low): finished batches are saved, the rest runs next time
    } catch (e: Exception) {
        CrashLog.note("cleanup failed: $e")
        Result.failure()
    }
}
