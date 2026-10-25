package com.aditya.album

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cosine similarity at or above which a face joins a group. SFace's own same-person cut-off is 0.363 for one face against
 * another (OpenCV Zoo, tuned on LFW); here a face is compared with a group's mean, which sits closer to the person's true
 * direction than any single face, so the bar can be higher. 0.45 favours splitting one person in two (fixed with one merge)
 * over mixing two people (fixed photo by photo). Calibration knob: raise it if strangers join people, lower it if one
 * person keeps showing up as several.
 */
const val SAME_PERSON = 0.45f

/**
 * Mean cosine similarity to a group's faces at or above which a face may join it: SFace's own pair cut-off. The centroid
 * alone isn't enough, since a loose group's centroid ends up close to every face: on the evaluation photos (see
 * FaceEvalTest) a dozen different children grew into one group that way. This floor splits it without costing adults.
 */
const val SAME_PERSON_ON_AVERAGE = 0.36f

/** Groups with fewer faces are not shown, unless the user named them: one-off faces are mostly strangers. */
const val MIN_FACES = 3

/** Cluster ids below this are people from people.db; from here up they are automatic groups from the face index. */
const val AUTO_CLUSTERS = 1L shl 32

/** A box that overlaps a remembered one this much (intersection over union) is the same face, even if found by a newer detector. */
private const val SAME_BOX = 0.5f

/** Where a face is in its photo, as fractions (0 to 1) of the upright photo's width and height. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** The same face after the photo is turned a quarter [clockwise] or anticlockwise. */
    fun rotated(clockwise: Boolean) = if (clockwise) FaceBox(1 - bottom, left, 1 - top, right) else FaceBox(top, 1 - right, bottom, 1 - left)
}

fun iou(a: FaceBox, b: FaceBox): Float {
    val w = min(a.right, b.right) - max(a.left, b.left)
    val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (w <= 0 || h <= 0) return 0f
    val overlap = w * h
    return overlap / ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - overlap)
}

/** One indexed face as clustering sees it. [embedding] has unit length; [cluster] is 0 when it has none yet. */
class FaceVector(val id: Long, val mediaId: Long, val box: FaceBox, val embedding: FloatArray, val cluster: Long)

/** A face the user put in a person (by naming its group or merging), found again after a rebuild by photo and box. */
class Confirmed(val personId: Long, val mediaId: Long, val box: FaceBox, val order: Long)

/** A person's mean face (unit length) and how many faces it was averaged from. */
class Centroid(val vector: FloatArray, val faces: Int)

/**
 * Everything the user told Album, from people.db. [people] maps every person id to its saved centroid (null if none
 * yet), [rejected] each photo's people it is "Not". These always win over automatic clustering.
 */
class Corrections(
    val people: Map<Long, Centroid?>,
    val confirmed: List<Confirmed>,
    val rejected: Map<Long, Set<Long>>,
)

/**
 * The faces whose cluster changed; each person's live centroid; automatic groups folded into another one (old id to
 * new); and the next free automatic id, to keep for the next run so a folded group's id is never handed out again.
 */
class Clustering(val changed: Map<Long, Long>, val centroids: Map<Long, Centroid>, val merged: Map<Long, Long>, val nextAuto: Long)

/**
 * Incremental clustering. Faces already in a valid cluster stay there, so groups don't reshuffle as photos arrive.
 * In order: confirmed faces go to their person; faces in a person that is gone, or in a photo marked "Not" them, are
 * freed; free faces (new ones included) join the most similar group's centroid if it reaches [threshold] and the face
 * matches the group's faces on average ([SAME_PERSON_ON_AVERAGE]), else start an automatic group numbered from
 * [nextAuto]. A face never joins a group that already has a face from its photo: one person isn't in a photo twice,
 * and look-alikes (a parent and child, siblings) are mostly photographed together. Then the groups that changed are
 * folded into others the same way (see mergeGroups). Faces and groups are visited by id, so the same input gives the
 * same result.
 */
fun cluster(faces: List<FaceVector>, corrections: Corrections, threshold: Float = SAME_PERSON, nextAuto: Long = AUTO_CLUSTERS): Clustering {
    val sorted = faces.sortedBy { it.id }
    val confirmed = corrections.confirmed.filter { it.personId in corrections.people }.groupBy { it.mediaId }
    fun rejects(cluster: Long, mediaId: Long) = cluster < AUTO_CLUSTERS && corrections.rejected[mediaId]?.contains(cluster) == true
    val assigned = HashMap<Long, Long>(sorted.size)
    for (f in sorted) {
        // Best overlapping confirmation; on a tie the latest one, as it's the user's most recent word
        val person = confirmed[f.mediaId].orEmpty()
            .map { it to iou(it.box, f.box) }
            .filter { it.second >= SAME_BOX }
            .maxWithOrNull(compareBy<Pair<Confirmed, Float>> { it.second }.thenBy { it.first.order })
            ?.first?.personId
        assigned[f.id] = when {
            person != null -> person
            f.cluster in 1 until AUTO_CLUSTERS && (f.cluster !in corrections.people || rejects(f.cluster, f.mediaId)) -> 0L
            else -> f.cluster
        }
    }

    val groups = sortedMapOf<Long, FaceGroup>()
    for (f in sorted) assigned.getValue(f.id).takeIf { it != 0L }?.let { groups.getOrPut(it) { FaceGroup() }.add(f) }
    // A person whose faces aren't indexed again yet (a rebuild in progress) still attracts new faces through its saved
    // centroid, weighted by the faces it came from so the first new face can't drag it away
    for ((id, saved) in corrections.people) if (id !in groups && saved != null) groups[id] = FaceGroup().apply { seed(saved) }
    var next = max(nextAuto, (groups.keys.lastOrNull() ?: 0L) + 1).coerceAtLeast(AUTO_CLUSTERS)

    for (f in sorted) {
        if (assigned.getValue(f.id) != 0L) continue
        var best = 0L
        var bestSimilarity = threshold
        for ((id, g) in groups) {
            if (rejects(id, f.mediaId) || f.mediaId in g.photos) continue
            val s = g.similarity(f.embedding)
            if (s >= bestSimilarity && (best == 0L || s > bestSimilarity) && g.meanSimilarity(f.embedding) >= SAME_PERSON_ON_AVERAGE) {
                best = id
                bestSimilarity = s
            }
        }
        if (best == 0L) best = next++
        groups.getOrPut(best) { FaceGroup() }.add(f)
        assigned[f.id] = best
    }

    // Only groups that changed can have become similar to another since the last run, so only they are looked at
    val grown = sorted.filter { assigned.getValue(it.id) != it.cluster }.mapTo(HashSet()) { assigned.getValue(it.id) }
    val merged = mergeGroups(assigned, groups, grown, threshold, corrections.rejected)

    val changed = sorted.filter { assigned.getValue(it.id) != it.cluster }.associate { it.id to assigned.getValue(it.id) }
    val centroids = groups.filter { (id, g) -> id < AUTO_CLUSTERS && id in corrections.people && g.faces > 0 }
        .mapValues { Centroid(it.value.centroid(), it.value.faces) }
    return Clustering(changed, centroids, merged, next)
}

/**
 * Folds groups together while their centroids reach [threshold] and their faces match on average: an automatic group
 * goes into a person, or the smaller automatic group into the bigger (then the lower id), so open pages mostly keep
 * their id. People never merge with each other by themselves, never take a group with a photo marked "Not" them, and
 * groups with a face in the same photo never merge. Groups that started apart (a first face in sunglasses, say) come
 * together this way once both have grown. The first round looks at the groups in [changed], later rounds at the groups
 * that grew in the round before. Returns each folded group's final group.
 */
private fun mergeGroups(
    assigned: HashMap<Long, Long>,
    groups: MutableMap<Long, FaceGroup>,
    changed: Set<Long>,
    threshold: Float,
    rejected: Map<Long, Set<Long>>,
): Map<Long, Long> {
    val blockedBy = HashMap<Long, Set<Long>>() // automatic group to the people "Not" in any of its photos
    fun blocked(group: Long) = blockedBy.getOrPut(group) { groups[group]?.photos.orEmpty().flatMapTo(HashSet()) { rejected[it].orEmpty() } }
    fun canMerge(a: Long, b: Long) = when {
        a < AUTO_CLUSTERS && b < AUTO_CLUSTERS -> false
        groups.getValue(a).sharesAPhotoWith(groups.getValue(b)) -> false
        a < AUTO_CLUSTERS -> a !in blocked(b)
        b < AUTO_CLUSTERS -> b !in blocked(a)
        else -> true
    }
    val into = HashMap<Long, Long>()
    var active = changed.toSortedSet()
    var rounds = 0
    while (active.isNotEmpty() && rounds++ < 10) {
        val grew = sortedSetOf<Long>()
        for (a in active) {
            val ga = groups[a] ?: continue
            val centroid = ga.centroid()
            var best = 0L
            var bestSimilarity = threshold
            for ((b, gb) in groups) {
                if (b == a || !canMerge(a, b)) continue
                val s = gb.similarity(centroid)
                if (s >= bestSimilarity && (best == 0L || s > bestSimilarity) && gb.meanSimilarity(ga) >= SAME_PERSON_ON_AVERAGE) {
                    best = b
                    bestSimilarity = s
                }
            }
            if (best == 0L) continue
            val gb = groups.getValue(best)
            val keepA = a < AUTO_CLUSTERS || (best >= AUTO_CLUSTERS && (ga.faces > gb.faces || (ga.faces == gb.faces && a < best)))
            val (from, to) = if (keepA) best to a else a to best
            groups.getValue(to).addAll(groups.getValue(from))
            groups.remove(from)
            blockedBy.remove(to)
            into[from] = to
            grew += to
            grew -= from
        }
        active = grew
    }
    if (into.isEmpty()) return emptyMap()
    fun final(group: Long): Long {
        var g = group
        while (true) g = into[g] ?: return g
    }
    for ((face, group) in assigned) assigned[face] = final(group)
    return into.keys.associateWith(::final)
}

/** Running sum of unit-length embeddings (its direction is the group's centroid) and the photos its faces are in. */
private class FaceGroup {
    private var sum: FloatArray? = null
    private var norm = 0f
    private var weight = 0f // faces summed, a saved centroid counting as the faces it came from
    val photos = HashSet<Long>()
    var faces = 0 // real faces in it; a saved centroid's weight doesn't count
        private set

    fun add(f: FaceVector) {
        addTimes(f.embedding, 1f)
        weight++
        photos += f.mediaId
        faces++
    }

    /** Starts from a saved centroid, as if it were that many faces. */
    fun seed(saved: Centroid) {
        val times = saved.faces.coerceAtLeast(1).toFloat()
        addTimes(saved.vector, times)
        weight += times
    }

    fun addAll(other: FaceGroup) {
        other.sum?.let { addTimes(it, 1f) }
        weight += other.weight
        photos += other.photos
        faces += other.faces
    }

    private fun addTimes(e: FloatArray, times: Float) {
        val s = sum ?: FloatArray(e.size).also { sum = it }
        for (i in e.indices) s[i] += e[i] * times
        norm = length(s)
    }

    /** Cosine similarity between unit-length [e] and this group's centroid. */
    fun similarity(e: FloatArray): Float {
        val s = sum ?: return -1f
        return if (norm == 0f) -1f else dot(s, e) / norm
    }

    /** Mean cosine similarity between unit-length [e] and this group's faces. */
    fun meanSimilarity(e: FloatArray): Float = sum?.let { dot(it, e) / weight } ?: -1f

    /** Mean cosine similarity between this group's faces and [other]'s. */
    fun meanSimilarity(other: FaceGroup): Float {
        val a = sum ?: return -1f
        val b = other.sum ?: return -1f
        return dot(a, b) / (weight * other.weight)
    }

    fun sharesAPhotoWith(other: FaceGroup) = photos.any { it in other.photos }

    fun centroid(): FloatArray = unit(sum!!)
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var d = 0f
    for (i in a.indices) d += a[i] * b[i]
    return d
}

fun length(v: FloatArray) = sqrt(dot(v, v))

/** [v] scaled to unit length, so dot products are cosine similarities. */
fun unit(v: FloatArray): FloatArray {
    val n = length(v)
    return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
}
