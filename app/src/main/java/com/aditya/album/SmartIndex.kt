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
import android.media.MediaMetadataRetriever
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Bump when analysis changes (labels, category mapping, place data, text) so every item is analysed again. */
private const val INDEX_VERSION = 3 // 3: labels down to 0.3 confidence, text read in more photos
private const val BATCH = 32 // items per database transaction; also how often progress moves
private const val THUMB_PX = 512 // ML Kit labels from a 224 px input, so a small cached thumbnail is plenty
// Search keeps labels down to this confidence (ML Kit's default is 0.5) and ranks by it, so a dog ML Kit is only fairly
// sure of still turns up for "dog", below the sure ones. Categories only count labels of CATEGORY_MIN and up, as before.
private const val LABEL_MIN = 0.3f
private const val CATEGORY_MIN = 0.5f
private const val MAX_LABELS = 20
private const val EDGE_PX = 256 // long side the edge count runs at: small text still makes edges, and it takes about a millisecond
// Long side for text recognition: a full screenshot (2400 px tall) shrinks only a little, and small print on a photographed
// receipt keeps the 16 px per character ML Kit needs. A thumbnail would blur both.
private const val TEXT_PX = 2048
// Prefs key holding the id of the photo being decoded in this process. A native crash in the decoder kills the app before
// it can catch anything, so the next pass finds the id still there and skips that photo's text instead of crashing again.
private const val DECODING = "decoding"
// Bump when Places changes how it names a GPS point: saved points are named again from their stored GPS (no file reads).
private const val PLACES_VERSION = 3 // 2: state borders, regions far from towns, video GPS. 3: GeoNames levels
// A photo without GPS takes the place of a photo with GPS taken at most this long before or after it, the same day
private const val ESTIMATE_MS = 2 * 60 * 60 * 1000L
private const val PLACES = "places"
private const val JOB = "Places and things" // on the Organising card

/** How far the current indexing pass has got. (0, 0) when there's nothing to do. */
data class IndexProgress(val done: Int, val total: Int)

/** A MediaStore item as the indexer sees it. [taken] is its [captureTime]: null unless this phone's camera took it. */
data class MediaStamp(val id: Long, val isVideo: Boolean, val modified: Long, val size: Long, val screenshot: Boolean = false, val taken: Long? = null)

class IndexPlan(val analyse: List<MediaStamp>, val remove: List<Long>)

/** A video's location as MediaMetadataRetriever gives it (ISO 6709, "+12.9716+077.5946/" or with altitude), or null. */
fun iso6709(location: String): DoubleArray? {
    val m = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(location) ?: return null
    val lat = m.groupValues[1].toDouble()
    val lng = m.groupValues[2].toDouble()
    return doubleArrayOf(lat, lng).takeIf { lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0) }
}

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
 * When a photo or video was taken, for estimating places: only for this phone's own camera photos and videos (in DCIM,
 * with a capture date in their metadata). A WhatsApp, downloaded or received file was made somewhere else, so when it
 * arrived says nothing about where it was taken: 30 forwarded "good morning" images aren't Goa because they arrived
 * during a Goa trip. Null for those and for screenshots.
 */
fun captureTime(dateTaken: Long, relativePath: String, name: String, screenshot: Boolean): Long? =
    dateTaken.takeIf { it > 0 && !screenshot && relativePath.startsWith("DCIM/", ignoreCase = true) && !isWhatsAppName(name) }

/**
 * Places for camera photos without GPS (location off; [without]: id to capture time) from camera photos with GPS
 * ([with]: id to capture time and place): the one taken nearest in time on the same day ([dayOf] gives an epoch day),
 * at most [ESTIMATE_MS] away.
 */
fun estimatePlaces(without: Map<Long, Long>, with: Map<Long, Pair<Long, Place>>, dayOf: (Long) -> Long): Map<Long, Place> {
    val sources = with.values.sortedBy { it.first }
    val times = LongArray(sources.size) { sources[it].first }
    val out = HashMap<Long, Place>()
    for ((id, time) in without) {
        val day = dayOf(time)
        var at = times.binarySearch(time).let { if (it < 0) -it - 1 else it } // the first source taken at or after it
        var best: Pair<Long, Place>? = null
        // Walk out both ways while sources are close enough; the nearest one taken the same day wins
        for (i in at - 1 downTo 0) {
            if (time - times[i] > ESTIMATE_MS) break
            if (dayOf(times[i]) == day) {
                best = sources[i]
                break
            }
        }
        while (at < times.size && times[at] - time <= ESTIMATE_MS) {
            if (dayOf(times[at]) == day) {
                if (best == null || times[at] - time < time - best.first) best = sources[at]
                break
            }
            at++
        }
        best?.let { out[id] = it.second }
    }
    return out
}

/**
 * On-device index of what's in each photo (ML Kit labels, a category), where it was taken (GeoNames levels from GPS
 * EXIF, or estimated from photos taken around it) and the text in photos that hold some. Nothing leaves the phone.
 * Read side for Explore and search: [categories], [places], [mediaIdsFor], [progress], [labels], [placeNames] and the
 * lookups.
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

    private fun progress(p: IndexProgress) {
        _progress.value = p
        IndexStatus.report(JOB, p.done, p.total)
    }

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

    /** Cities photos were taken in (estimated places included), most photos first. */
    fun places(): Flow<List<PlaceCount>> = dao.places()

    fun mediaIdsFor(category: Category): Flow<Set<Long>> = dao.ids(category).map { it.toSet() }

    fun mediaIdsFor(place: Place): Flow<Set<Long>> = dao.ids(place.city, place.admin1, place.countryCode).map { it.toSet() }

    /** Photos whose place is estimated from other photos, for the details to say so. */
    fun estimatedIds(): Flow<Set<Long>> = dao.estimated().map { it.toSet() }

    /** Every ML Kit label some photo has, for search to recognise. */
    fun labels(): Flow<List<String>> = dao.labels()

    /** Every place name some photo has, at every level (locality, city, district, state, country), for search. */
    fun placeNames(): Flow<List<String>> = dao.placeNames()

    // Blocking lookups for search: call them off the main thread.

    /** A category's photos, each with how sure ML Kit was of it (its strongest label of that category). */
    fun scoredIn(category: Category): Map<Long, Float> = dao.scoredIn(category, labelsOf(category)).associate { it.id to (it.score ?: 0f) }

    /** Photos with an ML Kit label, each with its confidence. */
    fun scoredWith(label: String): Map<Long, Float> = dao.scoredWith(label).associate { it.id to (it.score ?: 0f) }

    /** Photos taken in a locality, city, district, state or country of this name. */
    fun idsAt(placeName: String): Set<Long> = dao.idsAt(placeName).toSet()

    /** Photos with a word starting with [prefix] in them. [prefix] must be letters and digits only (no FTS syntax). */
    fun idsWithText(prefix: String): Set<Long> = dao.idsWithText("$prefix*").toSet()

    /** Photos with [tokens] in a row in their text, the last one the start of a word. Tokens as ftsTokens splits them. */
    fun idsWithTokens(tokens: List<String>): Set<Long> = dao.idsWithText(tokens.joinToString(" ", "\"", "*\"")).toSet()

    /** Photos with exactly [tokens] in a row in their text (a quoted search). Tokens as ftsTokens splits them. */
    fun idsWithPhrase(tokens: List<String>): Set<Long> = dao.idsWithText(tokens.joinToString(" ", "\"", "\"")).toSet()

    /** Other names people use for places, by GeoNames name (geonames/aliases.tsv): Bombay for Mumbai, Gurgaon for Gurugram. */
    val placeAliases: Map<String, List<String>> by lazy {
        app.assets.open("geonames/aliases.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line -> line.substringBefore('\t') to line.split('\t').drop(1) }
        }
    }

    /** Where photo [mediaId] was taken as the index has it, or null when it isn't indexed. Blocking: call off the main thread. */
    /** Where each photo with its own GPS was taken, for the map. Estimated places have no point, so they aren't here. */
    fun gps(): Flow<List<ItemGps>> = dao.gps()

    fun placeOf(mediaId: Long): ItemPlace? = dao.place(mediaId)

    /**
     * Forgets [ids] at once (labels, OCR text, place) instead of on the next pass: for photos moved into the Private
     * safe, so an ID card's text doesn't stay readable in the index.
     */
    suspend fun forget(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (::app.isInitialized) ids.chunked(500).forEach { dao.remove(it) } // under SQLite's 999-variable limit
    }

    // ---------- Places the user fixed (FixDetails.kt) ----------

    /** A spot in [place]: where one of its photos was taken, nearest the middle of them all. Null when none has GPS. */
    suspend fun spotIn(place: Place): DoubleArray? = withContext(Dispatchers.IO) {
        middleOf(dao.gpsIn(place.city, place.admin1, place.countryCode).map { doubleArrayOf(it.lat, it.lng) })
    }

    /** Where [ids] are as the index has them now, for Undo. */
    suspend fun spotsOf(ids: List<Long>): List<ItemSpot> = withContext(Dispatchers.IO) { ids.chunked(500).flatMap { dao.spots(it) } }

    suspend fun restoreSpots(spots: List<ItemSpot>) = withContext(Dispatchers.IO) { dao.setSpots(spots) }

    /**
     * Puts the places the user fixed into the index, so Places, search and the details show them: the place they picked,
     * at its spot when Album knows one, or no place. Only rows that differ are written.
     */
    suspend fun applyFixes(fixes: List<MediaFix>) = withContext(Dispatchers.IO) {
        val wanted = fixes.filter { it.placeFixed }.map { ItemSpot(it.mediaId, it.lat, it.lng, estimated = false, place = it.place) }
        val now = wanted.map { it.id }.chunked(500).flatMap { dao.spots(it) }.associateBy { it.id }
        dao.setSpots(wanted.filter { w -> now[w.id]?.let { it != w } == true }) // an item not indexed yet gets it at the end of its pass
    }

    /** Names every saved GPS point again on the next pass: a custom place was added, so photos taken there take its name. */
    fun renamePlaces() {
        if (::app.isInitialized) marks.edit(commit = true) { putInt(PLACES, 0) }
    }

    /** Text read from photo [mediaId], or null when it has none (or isn't indexed yet). Blocking: call off the main thread. */
    fun textOf(mediaId: Long): String? = dao.text(mediaId)

    /** How text reading went in a pass, for the breadcrumb: the cost is what decides how many photos it can look at. */
    private class TextStats {
        var looked = 0 // quick looks at a thumbnail
        var seen = 0 // ...that found text
        var read = 0 // full-size reads
        var found = 0 // ...that found text
        var ms = 0L // spent on all of it
    }

    /** One indexing pass: drops rows of deleted media, then analyses new and changed items in batches, newest first. */
    internal suspend fun run(onLongPass: suspend () -> Unit = {}) = withContext(background) {
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext
        val canLocate = has(ACCESS_MEDIA_LOCATION)
        // With "Select photos" access MediaStore lists only the picked ones: never treat the rest as deleted
        val partial = !has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO)
        // 236k places: parsed only if some photo in this pass has GPS, and dropped when the pass ends.
        val geo = lazy { app.assets.open("geonames/places.tsv").bufferedReader().useLines { Places.parse(it) } }
        // A place the user named wins within a few steps of it (see customPlaceAt)
        val custom = CustomPlaces.load(app).value
        val places = lazy { { lat: Double, lng: Double -> geo.value.at(lat, lng).let { p -> customPlaceAt(lat, lng, custom)?.let { namedPlace(p, it) } ?: p } } }
        var analysed = 0
        var failed = 0
        val text = TextStats()
        val started = SystemClock.elapsedRealtime()
        val crashed = marks.getLong(DECODING, 0L)
        if (crashed != 0L) CrashLog.note("smart index: a photo crashed the decoder last time, its text is skipped")
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(LABEL_MIN).setExecutor(thread).build())
        val reader = TextRecognition.getClient(TextRecognizerOptions.Builder().setExecutor(thread).build())
        var found: List<MediaStamp>
        try {
            do {
                // Never act on a failed or empty query: either would wipe the index.
                found = query()?.takeIf { it.isNotEmpty() } ?: return@withContext
                if (marks.getInt(PLACES, 1) < PLACES_VERSION) {
                    // Places are named differently now: name every saved GPS point again, from the GPS kept in the index
                    dao.located().chunked(500).forEach { batch -> dao.setPlaces(batch.associate { it.id to places.value(it.lat, it.lng) }) }
                    marks.edit(commit = true) { putInt(PLACES, PLACES_VERSION) }
                }
                val plan = planIndex(found, dao.stamps(), INDEX_VERSION, canLocate)
                if (!partial) plan.remove.chunked(500).forEach { dao.remove(it) }
                // Counted over the whole library, so a pass that resumes shows where it is, not a fresh start from 0
                progress(IndexProgress(found.size - plan.analyse.size, found.size))
                for ((i, batch) in plan.analyse.chunked(BATCH).withIndex()) {
                    if (plan.analyse.size - i * BATCH >= LONG_PASS) onLongPass()
                    val results = batch.map { item ->
                        try {
                            analyse(item, labeler, reader, canLocate, places, readText = item.id != crashed, text)
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
                    progress(_progress.value.let { it.copy(done = it.done + batch.size) })
                }
                analysed += plan.analyse.size
            } while (plan.analyse.isNotEmpty()) // photos taken during the pass are picked up before it ends
            // Places the user fixed win over GPS and estimates, while the file is as it was when they fixed it. They live in
            // collections.db, so they come back here after the index is analysed again or rebuilt.
            val stamps = found.associateBy { it.id }
            val fixed = runCatching { Collections.allFixes() }.getOrDefault(emptyList())
                .filter { it.placeFixed && stamps[it.mediaId]?.modified == it.modified }
            estimate(found, fixed.mapTo(HashSet()) { it.mediaId })
            applyFixes(fixed)
        } finally {
            labeler.close()
            reader.close()
            IndexStatus.report(JOB, 0, 0) // finished or stopped (battery low): off the card
        }
        if (analysed > 0) {
            val ms = (SystemClock.elapsedRealtime() - started) / analysed
            val textMs = text.ms / maxOf(1, text.looked + text.read)
            CrashLog.note(
                "smart index: $analysed items, $ms ms each, $failed unreadable, text: ${text.looked} looked at (${text.seen} with some), " +
                    "${text.read} read (${text.found} with some), $textMs ms each",
            )
        }
    }

    /**
     * Gives camera photos without GPS the place of a camera photo with GPS taken around the same time (see
     * [estimatePlaces]), marked estimated, and takes estimates back when their photos change. Only the phone's own
     * camera photos give or get one ([captureTime]); every other item's estimate is taken back. Places the user [fixed] are
     * left alone. Writes only what changed.
     */
    private suspend fun estimate(found: List<MediaStamp>, fixed: Set<Long>) {
        val stamps = found.associateBy { it.id }
        val rows = dao.itemPlaces().filter { it.id in stamps }
        val with = rows.mapNotNull { row ->
            val place = row.place?.takeIf { row.gps } ?: return@mapNotNull null
            stamps.getValue(row.id).taken?.let { row.id to (it to place) }
        }.toMap()
        val without = rows.filter { !it.gps && it.id !in fixed }
        val times = without.mapNotNull { row -> stamps.getValue(row.id).taken?.let { row.id to it } }.toMap()
        val zone = ZoneId.systemDefault()
        // An estimate claims the city, not the street: two hours is time enough to cross town
        val estimates = estimatePlaces(times, with) { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
            .mapValues { it.value.copy(locality = "") }
        val changed = without.filter { row -> row.place != estimates[row.id] || (row.place != null && !row.estimated) }
        changed.chunked(500).forEach { batch -> dao.setPlaces(batch.associate { it.id to estimates[it.id] }, estimated = true) }
    }

    private fun has(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Null if MediaStore didn't answer. */
    private fun query(): List<MediaStamp>? {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_MODIFIED, FileColumns.SIZE, FileColumns.RELATIVE_PATH,
            FileColumns.DISPLAY_NAME, FileColumns.DATE_TAKEN,
        )
        val selection = PHOTOS_AND_VIDEOS
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return app.contentResolver.query(uri, projection, selection, null, "${FileColumns._ID} DESC")?.use { c ->
            List(c.count) {
                c.moveToNext()
                val isVideo = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
                val path = c.getString(4).orEmpty()
                val name = c.getString(5).orEmpty()
                val screenshot = !isVideo && isScreenshot(path, name)
                MediaStamp(c.getLong(0), isVideo, c.getLong(2), c.getLong(3), screenshot, captureTime(c.getLong(6), path, name, screenshot))
            }
        }
    }

    private class Analysis(val item: IndexedItem, val labels: List<ItemLabel>, val text: ItemText?)

    // Saved with no category, like a corrupt file, so it's only tried again once the file changes
    private fun unreadable(item: MediaStamp) =
        IndexedItem(item.id, item.modified, item.size, INDEX_VERSION, category = null, lat = null, lng = null, located = true, place = null)

    // Videos are labelled from their thumbnail frame and located from their metadata. Their text isn't read.
    private suspend fun analyse(
        item: MediaStamp,
        labeler: ImageLabeler,
        reader: TextRecognizer,
        canLocate: Boolean,
        places: Lazy<(Double, Double) -> Place?>,
        readText: Boolean,
        stats: TextStats,
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
        val category = if (thumb != null && labels != null) categoryOf(labels.filter { it.second >= CATEGORY_MIN }, brightnessOf(thumb)) else null
        val text = if (thumb != null && labels != null && readText && !item.isVideo) {
            val started = SystemClock.elapsedRealtime()
            when (textChance(labels, item.screenshot, edgesOf(thumb))) {
                TextChance.Sure -> textIn(item.id, uri, reader, stats)
                // A quick read of the thumbnail first: big text (signs, posters, slides) shows there, leaves and bricks don't
                TextChance.Maybe -> {
                    stats.looked++
                    val seen = looksLikeText(runCatching { reader.process(InputImage.fromBitmap(thumb, 0)) }.getOrNull()?.await()?.text)
                    if (seen) stats.seen++
                    if (seen) textIn(item.id, uri, reader, stats) else null
                }
                TextChance.None -> null
            }.also { stats.ms += SystemClock.elapsedRealtime() - started }
        } else {
            null
        }
        thumb?.recycle()
        val gps = if (!canLocate) null else if (item.isVideo) videoLatLng(uri) else latLng(uri)
        val row = IndexedItem(
            id = item.id,
            modified = item.modified,
            size = item.size,
            version = INDEX_VERSION,
            category = category,
            lat = gps?.get(0),
            lng = gps?.get(1),
            located = canLocate,
            place = gps?.let { places.value(it[0], it[1]) },
        )
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
    private suspend fun textIn(id: Long, uri: Uri, reader: TextRecognizer, stats: TextStats): String? {
        stats.read++
        marks.edit(commit = true) { putLong(DECODING, id) }
        try {
            val bitmap = decode(uri) ?: return null
            return try {
                // A failed task comes back as null; this catches an image ML Kit rejects outright
                runCatching { reader.process(InputImage.fromBitmap(bitmap, 0)) }.getOrNull()?.await()?.text?.takeIf { it.isNotBlank() }
                    ?.also { stats.found++ }
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

    // The thumbnail's edge density (see edgeDensity) at EDGE_PX on its long side
    private fun edgesOf(bitmap: Bitmap): Float {
        val scale = minOf(1f, EDGE_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(2)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
        if (small !== bitmap) small.recycle()
        return edgeDensity(pixels, width)
    }

    // Android hides EXIF location unless the app holds ACCESS_MEDIA_LOCATION and asks for the original file. Read-only.
    private fun latLng(uri: Uri): DoubleArray? = try {
        app.contentResolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r")?.use { ExifInterface(it.fileDescriptor).latLong }
            ?.takeUnless { it[0] == 0.0 && it[1] == 0.0 } // some cameras write 0,0 when they had no fix
    } catch (e: Exception) {
        null
    }

    // A video's location is hidden the same way as a photo's GPS.
    private fun videoLatLng(uri: Uri): DoubleArray? = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(app, MediaStore.setRequireOriginal(uri))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709)
        }
    } catch (e: Exception) {
        null
    }
}

/** Runs [SmartIndex.run] under WorkManager: resumes after process death, waits while the battery is low. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val longPass = LongPassGate(this)

    override suspend fun doWork(): Result = try {
        SmartIndex.run(onLongPass = longPass::tick)
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped by WorkManager (battery low): finished batches are saved, the rest runs next time
    } catch (e: Exception) {
        CrashLog.note("smart index failed: $e")
        Result.failure()
    }
}
