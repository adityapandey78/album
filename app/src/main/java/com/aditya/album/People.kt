package com.aditya.album

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
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
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val BATCH = 16 // photos per database transaction; also how often progress moves
private const val CLUSTER_EVERY = 8 // batches between clustering runs, so People fills in during a long first pass
private const val NEXT_AUTO = "next_auto"
private const val DECODING = "decoding"

/**
 * Person pages open through the same album id as folders, [Special] albums, Explore pages and collections, in their
 * own band from 2^46: folder BUCKET_IDs are in the Int range, Special ids small multiples of 2^32, things 2^40 plus a
 * Category ordinal, places 2^44 plus a hash, collections from 2^48. Cluster ids stay under 2^46 (people count up from 1,
 * automatic groups from 2^32).
 */
private const val PEOPLE_IDS = 1L shl 46 // its own band: things start at 2^40, places at 2^44, collections at 2^48

fun personAlbumId(cluster: Long) = PEOPLE_IDS + cluster

/** The cluster behind an album id, or null for any other album. */
fun clusterOfAlbum(albumId: Long): Long? = if (albumId > PEOPLE_IDS && albumId < PEOPLE_IDS * 2) albumId - PEOPLE_IDS else null

/** A person or automatic group, with its photos still on the phone (newest first) and its best face as the cover. */
data class PersonAlbum(
    val cluster: Long,
    val name: String?,
    val hidden: Boolean,
    val cover: FaceCrop,
    val items: List<Media>,
    val faces: Int,
) {
    val albumId get() = personAlbumId(cluster)
}

/** Each cluster's photos in [media]'s order, its face count and best face, from [tiles]. Photos gone from the phone drop out. */
class Members<T>(val items: List<T>, val faces: Int, val cover: FaceTile)

fun <T> clusterMembers(tiles: List<FaceTile>, media: List<T>, idOf: (T) -> Long): Map<Long, Members<T>> {
    val position = HashMap<Long, Int>(media.size).also { m -> media.forEachIndexed { i, it -> m[idOf(it)] = i } }
    return tiles.filter { it.mediaId in position }.groupBy { it.cluster }.mapValues { (_, faces) ->
        val items = faces.mapTo(HashSet()) { it.mediaId }.map { position.getValue(it) }.sorted().map { media[it] }
        Members(items, faces.size, faces.maxWith(compareBy<FaceTile> { it.quality }.thenByDescending { it.id }))
    }
}

/** Whether a group appears in People: named people always, other groups once they have [MIN_FACES] faces. */
fun isShown(name: String?, faces: Int) = name != null || faces >= MIN_FACES

/** For search: each named person who isn't hidden, to the photos they're in. */
fun namedMedia(names: List<PersonName>, tiles: List<FaceTile>): Map<String, Set<Long>> {
    val byCluster = tiles.groupBy({ it.cluster }, { it.mediaId })
    val out = HashMap<String, MutableSet<Long>>()
    for (p in names) if (p.name != null && !p.hidden) byCluster[p.id]?.let { out.getOrPut(p.name) { HashSet() } += it }
    return out
}

/**
 * People in photos, found on the phone: ML Kit finds faces, SFace turns each into an embedding, and similar faces are
 * grouped. The face index (faces.db) is a cache that can be rebuilt any time; names, hidden people and every correction
 * live in people.db and always win over the automatic grouping. Read side: [albums], [mediaIdsByName], [progress].
 */
object People {
    private lateinit var app: Context
    // Small state that must outlive the face index: the next automatic id, and the photo being decoded (see run)
    private val prefs by lazy { app.getSharedPreferences("people", Context.MODE_PRIVATE) }
    private val faces by lazy {
        Room.databaseBuilder(app, FaceDb::class.java, "faces.db")
            .fallbackToDestructiveMigration(true) // rebuilt from the photos, so dropping it on a schema change is safe
            .build()
            .dao()
    }
    private val people by lazy { Room.databaseBuilder(app, PeopleDb::class.java, "people.db").build().dao() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clustering = Mutex() // the indexer and the user's corrections both re-cluster: one at a time

    // One background-priority thread for decoding, ML Kit and the embedding model, so the UI thread always wins the CPU.
    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "faces")
    }
    private val background = thread.asCoroutineDispatcher()

    private val _progress = MutableStateFlow(IndexProgress(0, 0))

    /** How far the current face scan has got, in photos. (0, 0) when there's nothing to do. */
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    // Old cluster to new, when naming, hiding or correcting an automatic group made it a person, or a merge moved it.
    // An open page follows its group through the id change instead of closing for the moment the lists disagree.
    private val moved = ConcurrentHashMap<Long, Long>()

    /** Where [cluster] went if it became, or merged into, another one in this session. */
    fun movedTo(cluster: Long): Long? {
        var to = moved[cluster] ?: return null
        repeat(64) { to = moved[to] ?: return to } // a chain is a few links long; the cap only guards against a cycle
        return to
    }

    /**
     * Every person and automatic group with a photo still on the phone, hidden ones included (see [isShown]). Grouped
     * off the main thread, and only while someone is watching: the Photos tab never pays for it.
     */
    val albums: StateFlow<List<PersonAlbum>> by lazy {
        combine(faces.tiles(), people.names(), Gallery.media.filterNotNull(), AlbumPrefs.hidden) { tiles, names, media, hidden ->
            val byId = names.associateBy { it.id }
            // Folders hidden from Photos stay out of People too, as they do from Explore and search
            clusterMembers(tiles, media.inPhotos(hidden)) { it.id }.map { (cluster, m) ->
                val person = byId[cluster]
                PersonAlbum(cluster, person?.name, person?.hidden == true, FaceCrop(m.cover.id, mediaUri(m.cover.mediaId, isVideo = false), m.cover.box), m.items, m.faces)
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** For search: each named person who isn't hidden, to the MediaStore ids of photos they're in. Live. */
    fun mediaIdsByName(): Flow<Map<String, Set<Long>>> = combine(people.names(), faces.tiles(), ::namedMedia).flowOn(Dispatchers.Default)

    /**
     * Call once from Application.onCreate. Scanning starts after the gallery's first load (so only once photo access
     * is granted) and runs again whenever the photos change. Unique work with KEEP: a running pass is never doubled.
     */
    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            Gallery.media.filterNotNull().collect {
                val request = OneTimeWorkRequestBuilder<FaceWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork("faces", ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    // --- Corrections. Each runs in this object's scope (so leaving a screen never drops one), then re-clusters at once.

    /**
     * Names [cluster], making it a person if it was an automatic group. A name another person already has (ignoring case)
     * merges the two instead. Returns the person's cluster id, which is new for an automatic group: open that page.
     */
    suspend fun name(cluster: Long, name: String): Long = edit {
        val trimmed = name.trim()
        val same = people.people().find { it.id != cluster && it.name.equals(trimmed, ignoreCase = true) }
        if (same != null) moveInto(cluster, same.id) else personOf(cluster).also { people.rename(it, trimmed) }
    }

    /** Merges [from] into [into]. The result keeps [into]'s name, or [from]'s if [into] had none. Returns its cluster id. */
    suspend fun merge(from: Long, into: Long): Long = edit { moveInto(from, into) }

    /** Hides or shows [cluster] in People. Returns its cluster id (new if it was an automatic group). */
    suspend fun setHidden(cluster: Long, hidden: Boolean): Long = edit {
        personOf(cluster).also { people.setHidden(it, hidden) }
    }

    /** "Not <name>": takes [mediaIds] out of [cluster] for good. Keep the result for [undo]. */
    suspend fun notPerson(cluster: Long, mediaIds: List<Long>): NotPersonChange = edit {
        val id = personOf(cluster)
        val faceIds = mediaIds.chunked(500).flatMap { faces.facesIn(id, it) }
        people.notPerson(id, mediaIds).let { NotPersonChange(it.personId, it.removed, it.added, faceIds) }
    }

    /** Puts back what [notPerson] changed, faces it had gained automatically included. */
    suspend fun undo(change: NotPersonChange) = edit {
        people.undo(change)
        faces.setClusters(change.faceIds.associateWith { change.personId })
    }

    private suspend fun <T> edit(block: suspend () -> T): T = scope.async {
        clustering.withLock { block().also { recluster() } }
    }.await()

    /**
     * [cluster] as a person: an automatic group becomes an unnamed person holding all its faces. A group that was folded
     * into another while this edit waited is followed there; one that's simply gone throws, so the edit reports failure
     * instead of landing on an empty person.
     */
    private suspend fun personOf(cluster: Long): Long {
        val current = live(cluster)
        if (current < AUTO_CLUSTERS) return current
        val id = people.create(Person(name = null, hidden = false, created = System.currentTimeMillis()), faces.spots(current))
        moved[current] = id
        faces.moveCluster(current, id)
        return id
    }

    /** [cluster], or where it went if it no longer has faces. */
    private suspend fun live(cluster: Long): Long = when {
        cluster < AUTO_CLUSTERS -> people.person(cluster)?.id ?: movedTo(cluster) ?: error("person $cluster is gone")
        faces.has(cluster) -> cluster
        else -> movedTo(cluster)?.let { live(it) } ?: error("group $cluster is gone")
    }

    private suspend fun moveInto(source: Long, into: Long): Long {
        val from = live(source)
        val target = personOf(into)
        if (from == target) return target
        val person = people.person(from)
        if (person == null) {
            // An automatic group: its faces become the person's
            people.insertFaces(faces.spots(from).map { PersonFace(personId = target, mediaId = it.mediaId, box = it.box) })
        } else {
            val t = people.person(target)
            if (t?.name == null && person.name != null) people.rename(target, person.name)
            if (t?.hidden == true && !person.hidden) people.setHidden(target, false) // stays hidden only if both were
            people.merge(person.id, target)
        }
        moved[from] = target
        faces.moveCluster(from, target) // its automatic faces too, not just the confirmed ones
        return target
    }

    /** Runs [cluster] over every face with the user's corrections, then saves the changes. Call holding [clustering]. */
    private suspend fun recluster() {
        val rows = faces.rows()
        val saved = people.people().associate { p ->
            p.id to p.centroid?.takeIf { p.centroidModel == FACE_MODEL }?.let { Centroid(it.toFloats(), p.centroidFaces) }
        }
        val corrections = Corrections(
            saved,
            people.faces().map { Confirmed(it.personId, it.mediaId, it.box, it.id) },
            people.notPerson().groupBy({ it.mediaId }, { it.personId }).mapValues { it.value.toSet() },
        )
        val vectors = rows.map { FaceVector(it.id, it.mediaId, it.box, it.embedding.toFloats(), it.cluster) }
        val nextAuto = prefs.getLong(NEXT_AUTO, AUTO_CLUSTERS)
        val result = cluster(vectors, corrections, nextAuto = nextAuto)
        // Ids of folded groups are never handed out again, so a page or an edit still holding one can't reach a stranger
        if (result.nextAuto != nextAuto) prefs.edit().putLong(NEXT_AUTO, result.nextAuto).commit()
        moved.putAll(result.merged)
        faces.setClusters(result.changed)
        for ((id, live) in result.centroids) {
            val old = saved[id]
            // A few re-found faces never replace a centroid made from many; unchanged ones aren't written back either
            if (old != null && (live.faces < old.faces || (live.faces == old.faces && dot(old.vector, live.vector) > 0.9999f))) continue
            people.setCentroid(id, live.vector.toBytes(), live.faces, FACE_MODEL)
        }
    }

    /**
     * Album turned photo [mediaId] a quarter [clockwise] or anticlockwise: turns the boxes of the faces the user
     * confirmed in it the same way, so they still match once the photo is scanned again. Call after the file is written.
     */
    fun rotated(mediaId: Long, clockwise: Boolean) {
        scope.launch {
            try {
                clustering.withLock { people.update(people.facesOf(mediaId).map { it.copy(box = it.box.rotated(clockwise)) }) }
            } catch (e: Exception) {
                CrashLog.note("people rotate failed: $e")
            }
        }
    }

    /** One scanning pass: drops faces of deleted photos, then scans new and changed photos in batches, newest first. */
    internal suspend fun run() = withContext(background) {
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext
        // With "Select photos" access MediaStore lists only the picked ones: never treat the rest as deleted
        val partial = !has(READ_MEDIA_IMAGES)
        var scanned = 0
        var found = 0
        var unreadable = 0
        val started = SystemClock.elapsedRealtime()
        val dropped = try {
            FaceAnalyser(app, thread).use { analyser ->
                do {
                    // Never act on a failed or empty query: either would wipe the index.
                    val photos = query()?.takeIf { it.isNotEmpty() } ?: break
                    skipCrashed(photos)
                    val plan = planIndex(photos, faces.stamps(), FACE_INDEX_VERSION, canLocate = false)
                    if (!partial) plan.remove.chunked(500).forEach { faces.delete(it) }
                    _progress.value = IndexProgress(0, plan.analyse.size)
                    plan.analyse.chunked(BATCH).forEachIndexed { i, batch ->
                        val results = batch.map { it to analyse(analyser, it.id) }
                        faces.save(
                            results.map { (photo, _) -> ScannedPhoto(photo.id, photo.modified, photo.size, FACE_INDEX_VERSION) },
                            results.flatMap { (photo, found) ->
                                found.orEmpty().map { IndexedFace(mediaId = photo.id, box = it.box, quality = it.quality, embedding = it.embedding.toBytes()) }
                            },
                        )
                        found += results.sumOf { it.second?.size ?: 0 }
                        unreadable += results.count { it.second == null }
                        _progress.update { it.copy(done = it.done + batch.size) }
                        if (i % CLUSTER_EVERY == CLUSTER_EVERY - 1) clustering.withLock { recluster() }
                    }
                    scanned += plan.analyse.size
                } while (plan.analyse.isNotEmpty()) // photos taken during the pass are picked up before it ends
                analyser.dropped
            }
        } finally {
            prefs.edit().remove(DECODING).commit() // finished or stopped by WorkManager: no photo to blame
        }
        _progress.value = IndexProgress(0, 0)
        clustering.withLock { recluster() }
        if (scanned > 0) {
            val ms = (SystemClock.elapsedRealtime() - started) / scanned
            CrashLog.note("faces: $scanned photos, $ms ms each, $found faces kept, $dropped dropped, $unreadable unreadable")
        }
    }

    /**
     * One photo's faces, or null if it can't be read. Decoding happens in this process, so a malformed file could crash
     * it (native code) or run it out of memory: the photo is noted first, and a later pass saves a photo that never
     * finished as unreadable instead of crashing on it again. An OutOfMemoryError just skips the photo.
     */
    private suspend fun analyse(analyser: FaceAnalyser, mediaId: Long): List<FoundFace>? {
        prefs.edit().putLong(DECODING, mediaId).commit()
        return try {
            analyser.analyse(mediaUri(mediaId, isVideo = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CrashLog.note("faces: photo skipped: $e")
            null
        }
    }

    /** Saves the photo the last pass died on (see [analyse]) as scanned with no faces, so it isn't tried again. */
    private suspend fun skipCrashed(photos: List<MediaStamp>) {
        val id = prefs.getLong(DECODING, -1).takeIf { it != -1L } ?: return
        photos.find { it.id == id }?.let { faces.save(listOf(ScannedPhoto(it.id, it.modified, it.size, FACE_INDEX_VERSION)), emptyList()) }
        prefs.edit().remove(DECODING).commit()
        CrashLog.note("faces: skipped a photo the last pass stopped on")
    }

    private fun has(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Photos (not videos) newest first. Null if MediaStore didn't answer. */
    private fun query(): List<MediaStamp>? {
        val projection = arrayOf(FileColumns._ID, FileColumns.DATE_MODIFIED, FileColumns.SIZE)
        val selection = "${FileColumns.MEDIA_TYPE} = ${FileColumns.MEDIA_TYPE_IMAGE}"
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return app.contentResolver.query(uri, projection, selection, null, "${FileColumns._ID} DESC")?.use { c ->
            List(c.count) {
                c.moveToNext()
                MediaStamp(c.getLong(0), isVideo = false, c.getLong(1), c.getLong(2))
            }
        }
    }
}

/** Runs [People.run] under WorkManager: resumes after process death, waits while the battery is low. */
class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        People.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped by WorkManager (battery low): finished batches are saved, the rest runs next time
    } catch (e: Exception) {
        CrashLog.note("faces failed: $e")
        Result.failure()
    }
}
