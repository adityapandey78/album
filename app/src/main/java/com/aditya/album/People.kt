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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
private const val NOT_SURE = "not_sure_faces" // "Same person?" answered Not sure, as the two faces shown (see NotSure)
private const val SUGGESTIONS = "suggestions" // the last clustering run's, so "Same person?" is there right after a restart
private const val JOB = "People" // on the Organising card

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

/**
 * A person or automatic group, with its photos still on the phone (newest first) and its cover face ([coverPinned] if the
 * user chose it). [locked]: hidden behind the privacy PIN, shown only in Hidden items.
 */
data class PersonAlbum(
    val cluster: Long,
    val name: String?,
    val hidden: Boolean,
    val cover: FaceCrop,
    val items: List<Media>,
    val faces: Int,
    val coverPinned: Boolean = false,
    val locked: Boolean = false,
) {
    val albumId get() = personAlbumId(cluster)
}

/** "Same person?": two people or groups that may be one, [a] the one with more faces. */
data class PeoplePair(val a: PersonAlbum, val b: PersonAlbum)

/** Each cluster's photos in [media]'s order, its face count and cover, from [tiles]. Photos gone from the phone drop out. */
class Members<T>(val items: List<T>, val faces: Int, val cover: FaceTile)

/** Groups [tiles] by cluster; [isCamera] and [pinned] (cluster to the face the user chose) pick covers, see [coverOf]. */
fun <T> clusterMembers(
    tiles: List<FaceTile>,
    media: List<T>,
    idOf: (T) -> Long,
    isCamera: (T) -> Boolean = { true },
    pinned: Map<Long, FaceSpot> = emptyMap(),
): Map<Long, Members<T>> {
    val position = HashMap<Long, Int>(media.size).also { m -> media.forEachIndexed { i, it -> m[idOf(it)] = i } }
    return tiles.filter { it.mediaId in position }.groupBy { it.cluster }.mapValues { (cluster, faces) ->
        val items = faces.mapTo(HashSet()) { it.mediaId }.map { position.getValue(it) }.sorted().map { media[it] }
        Members(items, faces.size, coverOf(faces, pinned[cluster]) { isCamera(media[position.getValue(it)]) })
    }
}

/**
 * How good a cover [face] makes: its picture quality (faceQuality), how typical of the person it is (its fit), and
 * camera photos over screenshots and forwards. Calibration knobs.
 */
fun coverScore(face: FaceTile, camera: Boolean) = face.quality * (0.5f + 0.5f * face.fit.coerceIn(0f, 1f)) * (if (camera) 1f else 0.8f)

/** The face to show for a person: the one the user [pinned] while it's still theirs, else the best by [coverScore]. */
fun coverOf(faces: List<FaceTile>, pinned: FaceSpot?, isCamera: (mediaId: Long) -> Boolean): FaceTile =
    pinned?.let { p -> faces.filter { it.mediaId == p.mediaId && iou(it.box, p.box) >= SAME_BOX }.maxByOrNull { iou(it.box, p.box) } }
        ?: faces.maxWith(compareBy<FaceTile> { coverScore(it, isCamera(it.mediaId)) }.thenByDescending { it.id })

/** Whether a group appears in People: named people always, other groups once they have [MIN_FACES] faces. */
fun isShown(name: String?, faces: Int) = name != null || faces >= MIN_FACES

/** For search: each named person who isn't hidden or locked, to the photos they're in. */
fun namedMedia(names: List<PersonName>, tiles: List<FaceTile>): Map<String, Set<Long>> {
    val byCluster = tiles.groupBy({ it.cluster }, { it.mediaId })
    val out = HashMap<String, MutableSet<Long>>()
    for (p in names) if (p.name != null && !p.hidden && !p.locked) byCluster[p.id]?.let { out.getOrPut(p.name) { HashSet() } += it }
    return out
}

/** People locked behind the privacy PIN ([ids]) and every photo they're in ([photos]). */
class LockedPeople(val ids: Set<Long>, val photos: Set<Long>)

/** [ids]' photos: the faces the face index groups with them, and the faces confirmed for them ([confirmed], photo ids). */
fun lockedPeople(ids: Collection<Long>, tiles: List<FaceTile>, confirmed: Collection<Long>): LockedPeople {
    val locked = ids.toHashSet()
    return LockedPeople(locked, tiles.filter { it.cluster in locked }.mapTo(HashSet(confirmed)) { it.mediaId })
}

/**
 * "Same person?" answered Not sure about the faces at [a] and [b] (photo and box). Kept by face rather than group id, so
 * whatever groups those faces are in later (an automatic group made a person, a rebuilt index) aren't asked about again.
 */
data class NotSure(val a: FaceSpot, val b: FaceSpot)

/** The group pairs (smaller id first) [answers] are about now, each face found again by photo and box in [tiles]. */
fun answeredPairs(answers: Collection<NotSure>, tiles: List<FaceTile>): Set<Pair<Long, Long>> {
    val byPhoto = tiles.groupBy { it.mediaId }
    fun clusterAt(spot: FaceSpot) = byPhoto[spot.mediaId].orEmpty()
        .map { it to iou(it.box, spot.box) }.filter { it.second >= SAME_BOX }.maxByOrNull { it.second }?.first?.cluster
    return answers.mapNotNullTo(HashSet()) { n ->
        val a = clusterAt(n.a) ?: return@mapNotNullTo null // photo deleted, or the face no longer in a group
        val b = clusterAt(n.b) ?: return@mapNotNullTo null
        minOf(a, b) to maxOf(a, b)
    }
}

// Not sure answers and suggestions as preference strings: "mediaId:left,top,right,bottom" spots, "a:b:score" pairs.
// Anything unreadable (an older format) is dropped rather than trusted.

fun encodeNotSure(n: NotSure) = listOf(n.a, n.b).joinToString("|") { "${it.mediaId}:${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}" }

fun decodeNotSure(text: String): NotSure? {
    val spots = text.split('|').map { part ->
        val box = part.substringAfter(':', "").split(',').mapNotNull { it.toFloatOrNull() }
        val mediaId = part.substringBefore(':').toLongOrNull()
        if (mediaId == null || box.size != 4) return null
        FaceSpot(mediaId, FaceBox(box[0], box[1], box[2], box[3]))
    }
    return if (spots.size == 2) NotSure(spots[0], spots[1]) else null
}

fun encodeSuggestions(suggestions: List<Suggestion>) = suggestions.joinToString(";") { "${it.a}:${it.b}:${it.score}" }

fun decodeSuggestions(text: String?): List<Suggestion> = text.orEmpty().split(';').mapNotNull { item ->
    val parts = item.split(':')
    val a = parts.getOrNull(0)?.toLongOrNull()
    val b = parts.getOrNull(1)?.toLongOrNull()
    val score = parts.getOrNull(2)?.toFloatOrNull()
    if (parts.size == 3 && a != null && b != null && score != null) Suggestion(a, b, score) else null
}

/**
 * "Same person?" pairs worth asking: both shown and not hidden or locked, not both named (two names mean two people), not
 * answered Not sure ([skipped], group pairs from [answeredPairs]). Most alike first.
 */
fun reviewPairs(suggestions: List<Suggestion>, albums: List<PersonAlbum>, skipped: Set<Pair<Long, Long>>): List<PeoplePair> {
    val byCluster = albums.associateBy { it.cluster }
    return suggestions.mapNotNull { s ->
        val a = byCluster[s.a] ?: return@mapNotNull null
        val b = byCluster[s.b] ?: return@mapNotNull null
        when {
            s.a to s.b in skipped -> null
            a.hidden || b.hidden || a.locked || b.locked || !isShown(a.name, a.faces) || !isShown(b.name, b.faces) -> null
            a.name != null && b.name != null -> null
            a.faces >= b.faces -> PeoplePair(a, b)
            else -> PeoplePair(b, a)
        }
    }
}

/**
 * People in photos, found on the phone: YuNet finds faces, EdgeFace-S turns each into an embedding, and similar faces are
 * grouped (see cluster()). The face index (faces.db) is a cache that can be rebuilt any time; names, hidden people, cover
 * choices and every correction live in people.db and always win over the automatic grouping. Read side: [albums],
 * [mediaIdsByName], [review], [progress].
 */
object People {
    private lateinit var app: Context
    // Small state that must outlive the face index: the next automatic id, the photo being decoded (see run), Not sure answers
    private val prefs by lazy { app.getSharedPreferences("people", Context.MODE_PRIVATE) }
    private val faces by lazy {
        Room.databaseBuilder(app, FaceDb::class.java, "faces.db")
            .fallbackToDestructiveMigration(true) // rebuilt from the photos, so dropping it on a schema change is safe
            .build()
            .dao()
    }
    private val people by lazy { Room.databaseBuilder(app, PeopleDb::class.java, "people.db").build().dao() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Forgets the faces found in [ids] at once, for photos moved into the Private safe (names and corrections stay). */
    suspend fun forget(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (!::app.isInitialized) return@withContext
        ids.chunked(500).forEach { chunk -> // under SQLite's 999-variable limit
            faces.deleteFaces(chunk)
            faces.delete(chunk)
        }
    }
    private val clustering = Mutex() // the indexer and the user's corrections both re-cluster: one at a time

    // Groups an edit gave faces (merges, new people, Undo) since the last clustering run: their faces moved without
    // cluster() seeing it, so it's told, and faces left out are tried against them. Only used holding [clustering].
    private val touched = HashSet<Long>()

    // One background-priority thread for decoding and the models, so the UI thread always wins the CPU.
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

    private fun progress(p: IndexProgress) {
        _progress.value = p
        IndexStatus.report(JOB, p.done, p.total)
    }

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
     * Every person and automatic group with a photo still on the phone, hidden and locked ones included (see [isShown]).
     * Grouped off the main thread, and only while someone is watching: the Photos tab never pays for it.
     */
    val albums: StateFlow<List<PersonAlbum>> by lazy {
        combine(faces.tiles(), people.names(), Gallery.media.filterNotNull(), AlbumPrefs.hidden, Hidden.state.filterNotNull()) { tiles, names, media, hidden, h ->
            val byId = names.associateBy { it.id }
            val pinned = names.mapNotNull { p -> p.coverMediaId?.let { id -> p.cover?.let { p.id to FaceSpot(id, it) } } }.toMap()
            // Folders hidden from Photos stay out of People too, as they do from Explore and search
            val photos = media.inPhotos(hidden)
            // Photos hidden behind the PIN leave everyone's page and cover; a locked person keeps all theirs, for Hidden items
            val shown = clusterMembers(tiles, photos.withoutHidden(h), { it.id }, ::isCamera, pinned) - h.people
            val locked = if (h.people.isEmpty()) emptyMap() else clusterMembers(tiles.filter { it.cluster in h.people }, photos, { it.id }, ::isCamera, pinned)
            (shown + locked).map { (cluster, m) ->
                val person = byId[cluster]
                val chosen = pinned[cluster]?.let { it.mediaId == m.cover.mediaId && iou(it.box, m.cover.box) >= SAME_BOX } == true
                PersonAlbum(
                    cluster, person?.name, person?.hidden == true, FaceCrop(m.cover.id, m.cover.mediaId, m.cover.box), m.items, m.faces, chosen,
                    locked = cluster in h.people,
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /**
     * People locked behind the privacy PIN and every photo they're in, the confirmed faces included, so a face index being
     * rebuilt never brings their photos back meanwhile. Reads the face index only while someone is locked.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    val locked: Flow<LockedPeople> by lazy {
        people.lockedIds().distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(LockedPeople(emptySet(), emptySet()))
            else combine(faces.tiles(), people.lockedPhotos()) { tiles, confirmed -> lockedPeople(ids, tiles, confirmed) }
        }
            .catch { // Hidden items would otherwise never load, and the Library waits for them
                CrashLog.note("locked people failed: $it")
                emit(LockedPeople(emptySet(), emptySet()))
            }
            .flowOn(Dispatchers.Default)
    }

    private fun isCamera(m: Media) = m.path.startsWith("DCIM/", ignoreCase = true)

    /** For search: each named person who isn't hidden, to the MediaStore ids of photos they're in. Live. */
    fun mediaIdsByName(): Flow<Map<String, Set<Long>>> = combine(people.names(), faces.tiles(), ::namedMedia).flowOn(Dispatchers.Default)

    // Pairs that may be one person, from the last clustering run
    private val suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    private val notSure = MutableStateFlow<Set<NotSure>>(emptySet())

    /** "Same person?" pairs to ask about, most alike first. Live. */
    val review: Flow<List<PeoplePair>> by lazy {
        combine(suggestions, albums, notSure, faces.tiles()) { s, a, n, t -> reviewPairs(s, a, answeredPairs(n, t)) }.flowOn(Dispatchers.Default)
    }

    /** [cluster]'s faces in photos still on the phone, best covers first: the cover picker's grid. Live. */
    fun facesOf(cluster: Long): Flow<List<FaceTile>> =
        combine(faces.tiles(), Gallery.media.filterNotNull(), AlbumPrefs.hidden, Hidden.state.filterNotNull()) { tiles, media, hidden, h ->
            // A photo hidden behind the PIN never becomes someone else's cover
            val photos = media.inPhotos(hidden).let { if (cluster in h.people) it else it.withoutHidden(h) }
            val shown = photos.associateBy { it.id }
            tiles.filter { it.cluster == cluster && it.mediaId in shown }
                .sortedWith(compareByDescending<FaceTile> { coverScore(it, isCamera(shown.getValue(it.mediaId))) }.thenBy { it.id })
        }.flowOn(Dispatchers.Default)

    /**
     * Call once from Application.onCreate. Scanning starts after the gallery's first load (so only once photo access
     * is granted) and runs again whenever the photos change. Unique work with KEEP: a running pass is never doubled.
     */
    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            notSure.update { it + prefs.getStringSet(NOT_SURE, emptySet()).orEmpty().mapNotNull(::decodeNotSure) }
            suggestions.compareAndSet(emptyList(), decodeSuggestions(prefs.getString(SUGGESTIONS, null)))
        }
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
     * merges the two instead, but only on the same side of Hidden items: naming someone after a hidden person never moves
     * them in there, or brings the hidden one out. Returns the person's cluster id, which is new for an automatic group:
     * open that page.
     */
    suspend fun name(cluster: Long, name: String): Long = edit {
        val trimmed = name.trim()
        val locked = people.person(live(cluster))?.locked == true
        val same = people.people().find { it.id != cluster && it.locked == locked && it.name.equals(trimmed, ignoreCase = true) }
        if (same != null) moveInto(cluster, same.id) else personOf(cluster).also { people.rename(it, trimmed) }
    }

    /** Merges [from] into [into]. The result keeps [into]'s name and cover, or [from]'s if [into] had none. Returns its cluster id. */
    suspend fun merge(from: Long, into: Long): Long = edit { moveInto(from, into) }

    /** Hides or shows [cluster] in People. Returns its cluster id (new if it was an automatic group). */
    suspend fun setHidden(cluster: Long, hidden: Boolean): Long = edit {
        personOf(cluster).also { people.setHidden(it, hidden) }
    }

    /**
     * Locks [cluster] behind the privacy PIN, or unlocks it. Locking confirms the faces the face index grouped with them,
     * so their photos stay hidden while a newer face model rescans the phone. Returns its cluster id (new if it was an
     * automatic group). No face moves, so unlike the other edits it doesn't re-cluster: hiding several people stays quick.
     */
    suspend fun setLocked(cluster: Long, locked: Boolean): Long = scope.async {
        clustering.withLock {
            val id = personOf(cluster)
            if (locked) {
                val confirmed = people.facesOfPerson(id)
                val fresh = faces.spots(id).filter { s -> confirmed.none { it.mediaId == s.mediaId && iou(it.box, s.box) >= SAME_BOX } }
                people.lock(id, fresh.map { PersonFace(personId = id, mediaId = it.mediaId, box = it.box) })
            } else {
                people.setLocked(id, false)
            }
            id
        }
    }.await()

    /** Pins [face] as [cluster]'s cover photo, or with null lets Album pick again. Returns its cluster id (new if it was an automatic group). */
    suspend fun setCover(cluster: Long, face: FaceTile?): Long = edit {
        personOf(cluster).also { people.setCover(it, face?.mediaId, face?.box?.left, face?.box?.top, face?.box?.right, face?.box?.bottom) }
    }

    /** "Same person?" answered Different: both become people (so the answer survives a rebuild) and are never offered again. */
    suspend fun different(a: Long, b: Long) = edit {
        val x = personOf(a)
        val y = personOf(b)
        if (x != y) people.insertDifferent(DifferentPeople(minOf(x, y), maxOf(x, y)))
    }

    /** "Same person?" answered Not sure about the two faces shown: whoever they belong to isn't asked about again. */
    fun notSure(pair: PeoplePair) {
        val answer = NotSure(FaceSpot(pair.a.cover.mediaId, pair.a.cover.box), FaceSpot(pair.b.cover.mediaId, pair.b.cover.box))
        notSure.update { it + answer }
        scope.launch { prefs.edit().putStringSet(NOT_SURE, notSure.value.mapTo(HashSet(), ::encodeNotSure)).apply() }
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
        touched += change.personId
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
        touched += id
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
            if (person.locked && t?.locked != true) people.setLocked(target, true) // locked if either was: never shown by accident
            val cover = person.cover
            if (t?.coverMediaId == null && person.coverMediaId != null && cover != null) {
                people.setCover(target, person.coverMediaId, cover.left, cover.top, cover.right, cover.bottom)
            }
            people.merge(person.id, target)
        }
        moved[from] = target
        faces.moveCluster(from, target) // its automatic faces too, not just the confirmed ones
        touched += target
        return target
    }

    /** Runs [cluster] over every face with the user's corrections, then saves the changes. Call holding [clustering]. */
    private suspend fun recluster() {
        val corrections = Corrections(
            people.people().mapTo(HashSet()) { it.id },
            people.faces().map { Confirmed(it.personId, it.mediaId, it.box, it.id) },
            people.notPerson().groupBy({ it.mediaId }, { it.personId }).mapValues { it.value.toSet() },
            people.different().mapTo(HashSet()) { it.a to it.b },
        )
        // A row from another model (an index that wasn't dropped) would be compared across spaces: left out
        val vectors = faces.rows().filter { it.embedding.size == EMBEDDING * 4 }
            .map { FaceVector(it.id, it.mediaId, it.box, it.embedding.toFloats(), it.cluster, it.weak, it.fit) }
        val nextAuto = prefs.getLong(NEXT_AUTO, AUTO_CLUSTERS)
        val result = cluster(vectors, corrections, nextAuto, touched)
        touched.clear()
        // Ids are never handed out again, so a page or an edit still holding a gone group's id can't reach a stranger
        if (result.nextAuto != nextAuto) prefs.edit().putLong(NEXT_AUTO, result.nextAuto).commit()
        faces.place(result.changed)
        if (result.suggestions != suggestions.value) prefs.edit().putString(SUGGESTIONS, encodeSuggestions(result.suggestions)).apply()
        suggestions.value = result.suggestions
    }

    /**
     * Album turned photo [mediaId] a quarter [clockwise] or anticlockwise: turns the boxes of the faces the user
     * confirmed or pinned as a cover in it the same way, so they still match once the photo is scanned again. Call
     * after the file is written.
     */
    fun rotated(mediaId: Long, clockwise: Boolean) {
        scope.launch {
            try {
                clustering.withLock { people.rotate(mediaId, clockwise) }
            } catch (e: Exception) {
                CrashLog.note("people rotate failed: $e")
            }
        }
    }

    /**
     * One scanning pass: drops faces of deleted photos, then scans new and changed photos in batches: photos with faces
     * the user confirmed first (so after a rebuild everyone named is back before anything else is grouped), then newest first.
     */
    internal suspend fun run(onLongPass: suspend () -> Unit = {}) = withContext(background) {
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext
        // With "Select photos" access MediaStore lists only the picked ones: never treat the rest as deleted
        val partial = !has(READ_MEDIA_IMAGES)
        var scanned = 0
        var found = 0
        var unreadable = 0
        val started = SystemClock.elapsedRealtime()
        val dropped = try {
            FaceAnalyser(app).use { analyser ->
                do {
                    // Never act on a failed or empty query: either would wipe the index.
                    val photos = query()?.takeIf { it.isNotEmpty() } ?: break
                    skipCrashed(photos)
                    val plan = planIndex(photos, faces.stamps(), FACE_INDEX_VERSION, canLocate = false)
                    if (!partial) plan.remove.chunked(500).forEach { faces.delete(it) }
                    val confirmed = people.faces().mapTo(HashSet()) { it.mediaId }
                    val todo = plan.analyse.sortedByDescending { it.id in confirmed } // stable: newest first within each
                    // Counted over the whole library, so a pass that resumes shows where it is, not a fresh start from 0
                    progress(IndexProgress(photos.size - todo.size, photos.size))
                    todo.chunked(BATCH).forEachIndexed { i, batch ->
                        if (todo.size - i * BATCH >= LONG_PASS) onLongPass()
                        val results = batch.map { it to analyse(analyser, it.id) }
                        faces.save(
                            results.map { (photo, _) -> ScannedPhoto(photo.id, photo.modified, photo.size, FACE_INDEX_VERSION) },
                            results.flatMap { (photo, found) ->
                                found.orEmpty().map { IndexedFace(mediaId = photo.id, box = it.box, quality = it.quality, weak = it.weak, embedding = it.embedding.toBytes()) }
                            },
                        )
                        found += results.sumOf { it.second?.size ?: 0 }
                        unreadable += results.count { it.second == null }
                        progress(_progress.value.let { it.copy(done = it.done + batch.size) })
                        if (i % CLUSTER_EVERY == CLUSTER_EVERY - 1) clustering.withLock { recluster() }
                    }
                    scanned += todo.size
                } while (todo.isNotEmpty()) // photos taken during the pass are picked up before it ends
                analyser.dropped.toString() // {crowd=3, small=40, unlike a face=12}
            }
        } finally {
            prefs.edit().remove(DECODING).commit() // finished or stopped by WorkManager: no photo to blame
            progress(IndexProgress(0, 0))
        }
        clustering.withLock { recluster() }
        if (scanned > 0) {
            val ms = (SystemClock.elapsedRealtime() - started) / scanned
            CrashLog.note("faces: $scanned photos, $ms ms each, $found faces kept, dropped $dropped, $unreadable unreadable")
        }
    }

    /**
     * One photo's faces, or null if it can't be read. Decoding happens in this process, so a malformed file could crash
     * it (native code) or run it out of memory: the photo is noted first, and a later pass saves a photo that never
     * finished as unreadable instead of crashing on it again. An OutOfMemoryError just skips the photo.
     */
    private fun analyse(analyser: FaceAnalyser, mediaId: Long): List<FoundFace>? {
        prefs.edit().putLong(DECODING, mediaId).commit()
        return try {
            analyser.analyse(mediaUri(mediaId, isVideo = false))
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
    private val longPass = LongPassGate(this)

    override suspend fun doWork(): Result = try {
        People.run(onLongPass = longPass::tick)
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped by WorkManager (battery low): finished batches are saved, the rest runs next time
    } catch (e: Exception) {
        CrashLog.note("faces failed: $e")
        Result.failure()
    }
}
