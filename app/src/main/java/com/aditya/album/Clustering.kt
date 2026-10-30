package com.aditya.album

import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Thresholds are cosine similarities between EdgeFace-S embeddings, calibrated on Album's own faces from the evaluation
// photos (FaceEvalTest, tools/face_eval.py): two strong photos of one person scored 0.41 to 0.95 (median 0.71), two
// different people never more than 0.39 in 7,266 pairs (99.9% under 0.34). A real library has far more pairs, so each
// threshold keeps a margin over the largest different-people score. Precision first: a person split in two costs one
// "Same person?" answer, two people mixed cost photo-by-photo fixing.

/**
 * Two strong faces at least this alike may start a person together, and two starting groups join when their faces
 * average this much (average linkage, so one look-alike pair can't chain two people).
 */
const val SAME_PERSON = 0.45f

/**
 * A face joins a person or group when the mean of its [TOP] best matches among the group's exemplars reaches this
 * (on the evaluation photos a face scored 0.56 or more against its own person, at most 0.33 against anyone else)...
 */
const val ATTACH = 0.45f

/** ...or this for a weak face (small, turned, blurred): those match strangers more easily. */
const val ATTACH_WEAK = 0.55f

/** ...and beats every other group by this much. Otherwise the face is left out (unsure) rather than guessed. */
const val MARGIN = 0.1f

/** Groups whose faces average this much are offered as "Same person?" (never merged by themselves; two different people averaged 0.2 at most). */
const val SUGGEST = 0.3f

/** Groups with fewer faces are not shown, unless the user named them: one-off faces are mostly strangers. */
const val MIN_FACES = 3

/**
 * The bars [cluster] groups by, defaulting to the ones above for faces ([FACES]): [same] for [SAME_PERSON], [attach] and
 * [attachWeak] for [ATTACH] and [ATTACH_WEAK], [margin] for [MARGIN], [suggest] for [SUGGEST], [min] for [MIN_FACES].
 * Pets group whole photos by describe-it vectors, a different space, with bars of their own (PETS in Pets.kt).
 */
class Bars(val same: Float, val attach: Float, val attachWeak: Float, val margin: Float, val suggest: Float, val min: Int)

val FACES = Bars(SAME_PERSON, ATTACH, ATTACH_WEAK, MARGIN, SUGGEST, MIN_FACES)

/** Cluster ids below this are people from people.db; from here up they are automatic groups from the face index. */
const val AUTO_CLUSTERS = 1L shl 32

/** The cluster of a face that was looked at and belongs to nobody yet (0 is a face not looked at yet). */
const val UNSURE = -1L

private const val TOP = 3 // best exemplar matches averaged: one odd photo can't pull a face in
private const val EXEMPLARS = 16 // per group, chosen to cover its looks (ages, glasses, angles)

/** A box that overlaps a remembered one this much (intersection over union) is the same face, even if found by a newer detector. */
const val SAME_BOX = 0.5f

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

/**
 * One indexed face as clustering sees it. [embedding] has unit length. [cluster] is 0 for a face not looked at yet,
 * [UNSURE], or its person or group. [fit]: for a face in a group, how well it fits it (for choosing covers); for an
 * unsure face, its best score when it was left out.
 */
class FaceVector(
    val id: Long,
    val mediaId: Long,
    val box: FaceBox,
    val embedding: FloatArray,
    val cluster: Long,
    val weak: Boolean = false,
    val fit: Float = 0f,
)

/** A face the user put in a person (naming its group, merging, "Same"), found again after a rebuild by photo and box. */
class Confirmed(val personId: Long, val mediaId: Long, val box: FaceBox, val order: Long)

/**
 * Everything the user told Album, from people.db: the [people] there, faces they [confirmed], each photo's people it is
 * "Not" ([rejected]), and pairs of people they said are [different]. These always win over automatic clustering.
 */
class Corrections(
    val people: Set<Long>,
    val confirmed: List<Confirmed>,
    val rejected: Map<Long, Set<Long>>,
    val different: Set<Pair<Long, Long>> = emptySet(),
)

/** Two groups that may be one person, for "Same person?": [a] < [b], [score] their strong faces' average similarity. */
data class Suggestion(val a: Long, val b: Long, val score: Float)

/** Where a face goes and how well it fits there (see [FaceVector.fit]). */
data class Placed(val cluster: Long, val fit: Float)

/** The faces whose cluster or fit changed, the next free automatic id (kept for the next run), and the suggestions. */
class Clustering(val changed: Map<Long, Placed>, val nextAuto: Long, val suggestions: List<Suggestion>)

/**
 * Incremental clustering. Faces already in a group stay there, so groups don't reshuffle as photos arrive. In order:
 * 1. The user's word: confirmed faces go to their person; faces in a person that is gone, or in a photo marked "Not"
 *    them, are looked at again.
 * 2. Faces not looked at yet join the group whose exemplars they match best (mean of the top [TOP]), if that reaches
 *    [ATTACH] ([ATTACH_WEAK] for weak faces) and beats the next group by [MARGIN].
 * 3. Strong faces still free, with the unsure ones, start new automatic groups where at least [MIN_FACES] of them
 *    hold together ([SAME_PERSON], average linkage). Unsure faces are compared with new faces only: two of them were
 *    already compared when the later one was new.
 * 4. The rest try again against the groups that changed. An unsure face that now reaches the bar against one of them
 *    is scored against every group, so it joins only if it still beats them all by [MARGIN]. Faces still free
 *    become [UNSURE]. [touched] names groups that gained faces outside this function since the last run (the user's
 *    merges and new people), so they count as changed too.
 * Never: two faces of one photo in one group, a face in a person its photo is "Not", groups merged by themselves
 * (likely ones become [Clustering.suggestions]). Faces are visited by id, so the same input gives the same result.
 * The bars named are the faces'; [bars] swaps them all, for pets.
 */
fun cluster(
    faces: List<FaceVector>,
    corrections: Corrections,
    nextAuto: Long = AUTO_CLUSTERS,
    touched: Set<Long> = emptySet(),
    bars: Bars = FACES,
): Clustering {
    val sorted = faces.sortedBy { it.id }
    val confirmed = corrections.confirmed.filter { it.personId in corrections.people }.groupBy { it.mediaId }
    fun rejects(group: Long, mediaId: Long) = group < AUTO_CLUSTERS && corrections.rejected[mediaId]?.contains(group) == true

    val placed = HashMap<Long, Placed>()
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
    for (f in sorted) assigned.getValue(f.id).takeIf { it > 0 }?.let { groups.getOrPut(it) { FaceGroup() }.add(f) }
    val changed = touched.filterTo(HashSet()) { it in groups } // groups that gained faces
    for (f in sorted) if (assigned.getValue(f.id) != f.cluster && assigned.getValue(f.id) > 0) changed += assigned.getValue(f.id)

    fun join(f: FaceVector, group: Long, fit: Float) {
        assigned[f.id] = group
        groups.getOrPut(group) { FaceGroup() }.add(f)
        changed += group
        placed[f.id] = Placed(group, fit)
    }

    // 2. New faces against every group
    val fresh = sorted.filter { assigned.getValue(it.id) == 0L }
    var exemplars = groups.mapValues { it.value.exemplars() }
    val firstTry = fresh.associateWith { f -> scores(f, exemplars.keys, exemplars, groups, ::rejects) }
    fun joins(f: FaceVector, s: Scores) = attaches(f, s, bars)
    for ((f, s) in settle(firstTry, ::joins)) join(f, s.group, s.best)

    // 3. New groups from strong free faces that hold together
    var next = max(nextAuto, (groups.keys.lastOrNull() ?: 0L) + 1).coerceAtLeast(AUTO_CLUSTERS)
    val unsure = sorted.filter { assigned.getValue(it.id) == UNSURE }
    val free = fresh.filter { assigned.getValue(it.id) == 0L }
    for (core in cores(free.filter { !it.weak }, unsure.filter { !it.weak }, bars)) {
        val id = next++
        val sum = FloatArray(core[0].embedding.size).also { s -> core.forEach { f -> for (i in s.indices) s[i] += f.embedding[i] } }
        for (f in core) join(f, id, (dot(sum, f.embedding) - 1) / (core.size - 1)) // its average similarity to the rest
    }

    // 4. Everything still free against the groups that changed
    exemplars = groups.filterKeys { it in changed }.mapValues { it.value.exemplars() }
    val retry = HashMap<FaceVector, Scores>()
    for (f in free) if (assigned.getValue(f.id) == 0L) {
        // Its first try already scored the groups that didn't change
        val unchangedBest = firstTry.getValue(f).all.filterKeys { it !in changed }.values.maxOrNull() ?: -1f
        retry[f] = scores(f, exemplars.keys, exemplars, groups, ::rejects).withFloor(unchangedBest)
    }
    // An unsure face's earlier scores may be against the very group that grew, so none of them is reused: one that now
    // reaches the bar is scored against every group (computed once, and only if some face needs it)
    val everyone by lazy { groups.mapValues { it.value.exemplars() } }
    for (f in unsure) if (assigned.getValue(f.id) == UNSURE && f.id !in placed) {
        if (scores(f, exemplars.keys, exemplars, groups, ::rejects).best < (if (f.weak) bars.attachWeak else bars.attach)) continue
        retry[f] = scores(f, everyone.keys, everyone, groups, ::rejects)
    }
    for ((f, s) in settle(retry, ::joins)) join(f, s.group, s.best)
    // New and freed faces still out become unsure (the ones unsure already stay as they are)
    for ((f, s) in retry) if (f.id !in placed && f.cluster != UNSURE) placed[f.id] = Placed(UNSURE, max(s.best, s.floor))

    // Faces the user's word moved: how well they match their person, for choosing its cover
    val moved = sorted.filter { assigned.getValue(it.id).let { g -> g > 0 && g != it.cluster } && it.id !in placed }
    exemplars = moved.map { assigned.getValue(it.id) }.distinct().associateWith { groups.getValue(it).exemplars() }
    for (f in moved) assigned.getValue(f.id).let { placed[f.id] = Placed(it, score(f, exemplars.getValue(it))) }

    val before = sorted.associateBy { it.id }
    val result = placed.filter { (id, p) -> before.getValue(id).let { it.cluster != p.cluster || it.fit != p.fit } }
    return Clustering(result, next, suggestions(groups, corrections, bars))
}

/** A face's scores against groups: the best one, its [group], and the runner-up (at least [floor], see withFloor). */
private class Scores(val all: Map<Long, Float>, val floor: Float = -1f) {
    val group = all.maxByOrNull { it.value }?.key ?: 0L
    val best = all[group] ?: -1f
    val runnerUp = max(floor, all.filterKeys { it != group }.values.maxOrNull() ?: -1f)

    /** The same scores, with groups not scored now counted as one scoring [floor]. */
    fun withFloor(floor: Float) = Scores(all, floor)
}

private fun scores(f: FaceVector, candidates: Set<Long>, exemplars: Map<Long, List<FaceVector>>, groups: Map<Long, FaceGroup>, rejects: (Long, Long) -> Boolean) =
    Scores(candidates.filter { g -> f.mediaId !in groups.getValue(g).photos && !rejects(g, f.mediaId) }.associateWith { score(f, exemplars.getValue(it)) })

private fun attaches(f: FaceVector, s: Scores, bars: Bars) =
    s.group != 0L && s.best >= (if (f.weak) bars.attachWeak else bars.attach) && s.best - s.runnerUp >= bars.margin

/** The faces that join a group: of several faces from one photo choosing one group, only the best match does. */
private fun settle(tries: Map<FaceVector, Scores>, attaches: (FaceVector, Scores) -> Boolean): List<Pair<FaceVector, Scores>> =
    tries.entries.filter { (f, s) -> attaches(f, s) }
        .groupBy { (f, s) -> f.mediaId to s.group }
        .map { (_, bids) -> bids.maxWith(compareBy<Map.Entry<FaceVector, Scores>> { it.value.best }.thenByDescending { it.key.id }) }
        .map { it.key to it.value }
        .sortedBy { it.first.id }

/** Mean of the [TOP] best similarities between [f] and [exemplars] other than itself (all of them when there are fewer). */
fun score(f: FaceVector, exemplars: List<FaceVector>): Float {
    val best = exemplars.filter { it.id != f.id }.map { dot(f.embedding, it.embedding) }.sortedDescending()
    return if (best.isEmpty()) -1f else best.take(TOP).average().toFloat()
}

/**
 * Groups of at least [MIN_FACES] faces among [fresh] and [old] that hold together: agglomerative clustering, average
 * linkage, starting from pairs at least [SAME_PERSON] alike and joining groups while all their faces average that much.
 * Groups sharing a photo never join. Pairs of two [old] faces aren't compared (see [cluster]).
 */
fun cores(fresh: List<FaceVector>, old: List<FaceVector>, bars: Bars = FACES): List<List<FaceVector>> {
    val all = (fresh + old).sortedBy { it.id }
    val isFresh = fresh.mapTo(HashSet()) { it.id }
    val n = all.size
    val sums = Array(n) { all[it].embedding.copyOf() }
    val sizes = IntArray(n) { 1 }
    val photos = Array(n) { hashSetOf(all[it].mediaId) }
    val members = Array(n) { mutableListOf(all[it]) }
    val neighbours = Array(n) { HashSet<Int>() }
    val version = IntArray(n)
    class Pair(val score: Float, val a: Int, val b: Int, val va: Int, val vb: Int)
    val queue = PriorityQueue(compareByDescending<Pair> { it.score }.thenBy { it.a }.thenBy { it.b })
    for (i in 0 until n) for (j in i + 1 until n) {
        if ((all[i].id !in isFresh && all[j].id !in isFresh) || all[i].mediaId == all[j].mediaId) continue
        val s = dot(all[i].embedding, all[j].embedding)
        if (s < bars.same) continue
        neighbours[i] += j
        neighbours[j] += i
        queue += Pair(s, i, j, 0, 0)
    }
    while (queue.isNotEmpty()) {
        val p = queue.poll()
        val (a, b) = p.a to p.b
        if (version[a] != p.va || version[b] != p.vb || sizes[a] == 0 || sizes[b] == 0) continue // stale: a side changed since
        if (photos[a].any { it in photos[b] }) continue
        // b into a
        for (i in sums[a].indices) sums[a][i] += sums[b][i]
        sizes[a] += sizes[b]
        sizes[b] = 0
        photos[a] += photos[b]
        members[a] += members[b]
        neighbours[a] += neighbours[b]
        neighbours[a] -= setOf(a, b)
        version[a]++
        for (c in neighbours[a]) {
            if (sizes[c] == 0) continue
            neighbours[c] -= b
            neighbours[c] += a
            val average = dot(sums[a], sums[c]) / (sizes[a] * sizes[c])
            if (average >= bars.same) queue += Pair(average, min(a, c), max(a, c), version[min(a, c)], version[max(a, c)])
        }
    }
    return (0 until n).filter { sizes[it] >= bars.min && members[it].any { f -> f.id in isFresh } }.map { members[it].sortedBy { f -> f.id } }
}

/**
 * Pairs of shown groups whose strong faces average [SUGGEST] or more, most alike first, leaving out pairs that share a
 * photo (two people for sure), that the user said are different, or where a photo of one is marked "Not" the other.
 */
private fun suggestions(groups: Map<Long, FaceGroup>, corrections: Corrections, bars: Bars): List<Suggestion> {
    fun notOf(person: Long, g: FaceGroup) = person < AUTO_CLUSTERS && g.photos.any { corrections.rejected[it]?.contains(person) == true }
    val shown = groups.filter { (id, g) -> (id < AUTO_CLUSTERS || g.faces.size >= bars.min) && g.strong > 0 }.toSortedMap()
    val ids = shown.keys.toList()
    val out = ArrayList<Suggestion>()
    for (i in ids.indices) for (j in i + 1 until ids.size) {
        val a = shown.getValue(ids[i])
        val b = shown.getValue(ids[j])
        if ((ids[i] to ids[j]) in corrections.different || (ids[j] to ids[i]) in corrections.different) continue
        if (a.photos.any { it in b.photos } || notOf(ids[i], b) || notOf(ids[j], a)) continue
        val average = dot(a.sum, b.sum) / (a.strong * b.strong)
        if (average >= bars.suggest) out += Suggestion(ids[i], ids[j], average)
    }
    return out.sortedByDescending { it.score }
}

/** A person or automatic group as clustering sees it: its faces and the photos they're in. */
private class FaceGroup {
    val faces = ArrayList<FaceVector>()
    val photos = HashSet<Long>()
    /** Sum of the strong faces' embeddings and their count: average similarity to another group is dot(sums) / counts. */
    var sum = FloatArray(0)
        private set
    var strong = 0
        private set

    fun add(f: FaceVector) {
        faces += f
        photos += f.mediaId
        if (f.weak) return
        if (sum.isEmpty()) sum = FloatArray(f.embedding.size)
        for (i in sum.indices) sum[i] += f.embedding[i]
        strong++
    }

    /**
     * Up to [EXEMPLARS] faces covering the group's looks: the most typical strong face, then again and again the face
     * least like any chosen so far. Weak faces only when there are no strong ones.
     */
    fun exemplars(): List<FaceVector> {
        val pool = faces.filter { !it.weak }.ifEmpty { faces }.sortedBy { it.id }
        if (pool.size <= EXEMPLARS) return pool
        val total = FloatArray(pool[0].embedding.size).also { s -> pool.forEach { f -> for (i in s.indices) s[i] += f.embedding[i] } }
        val chosen = arrayListOf(pool.maxBy { dot(it.embedding, total) })
        val nearest = FloatArray(pool.size) { dot(pool[it].embedding, chosen[0].embedding) }
        while (chosen.size < EXEMPLARS) {
            val next = nearest.indices.minBy { nearest[it] }
            chosen += pool[next]
            for (i in pool.indices) nearest[i] = max(nearest[i], dot(pool[i].embedding, pool[next].embedding))
        }
        return chosen
    }
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
