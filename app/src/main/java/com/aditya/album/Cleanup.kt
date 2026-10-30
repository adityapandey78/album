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
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bump when measuring changes (decode size, hash, sharpness) so every photo is measured again. 2: PDQ and tiled blur (v0.10). */
private const val CLEANUP_VERSION = 2
private const val BATCH = 32 // items per database transaction; also how often progress moves
/** Long side photos are decoded at for measuring: big enough to show defocus, and PDQ reads the same at any size. */
const val LOOK_PX = 1024
private const val JOB = "Clean-up" // as IndexStatus shows it; For you looks the pass up by this name

// Tuning knobs, set conservatively: a missed suggestion costs nothing, a wrong one costs trust. CleanupEvalTest prints how
// each does on its photos (copies, crops, bursts, night shots, documents, blur); retune there, then on a real library.

/** PDQ bits two copies of one photo may differ in: Meta's threshold. Recompressed, resized and shared copies stay well inside. */
const val COPY_DISTANCE = 31
/** PDQ quality below this (dark, plain or nearly empty pictures) gives hashes too alike to trust: such photos only match exactly. */
const val MIN_QUALITY = 50
/**
 * Similar shots differ in at most this many PDQ bits (tune between 32 and 72): in CleanupEvalTest a burst is 6 to 20 apart
 * and a fixed-camera time-lapse 42 s apart 52, while different scenes shot seconds apart are 82 and more...
 */
const val SIMILAR_DISTANCE = 56
/** ...are taken at most this far apart... */
const val SIMILAR_WINDOW_MS = 3 * 60 * 1000L
/** ...and a group of them spans at most this long. */
const val SIMILAR_SPAN_MS = 5 * 60 * 1000L
/**
 * Below this [CleanupRow.blur] a photo with enough texture reads as blurry... In CleanupEvalTest sharp photos score 0.06
 * and up (0.038 with only a small sharp subject), slightly defocused or blurred ones 0.025 and under.
 */
const val BLUR_SHARPNESS = 0.035f
/** ...and below this one for night shots, whose noise reduction smooths away fine detail even when they're in focus. */
const val BLUR_SHARPNESS_NIGHT = 0.025f
/**
 * A face at least this sharp (same measure, on its box) means the photo is in focus where it matters. Skin is smooth, so
 * a face scores far below a textured tile: 0.008 to 0.03 sharp, 0.005 and under blurred, in CleanupEvalTest. Set at the
 * blurred end, since a sharp face wrongly called blurry costs more than a blurry one let off.
 */
const val FACE_SHARPNESS = 0.005f
/** The photo's sharpness is its sharpest tiles': this share of textured tiles are softer. A small sharp subject is enough. */
const val SHARPEST_TILES = 0.95f
/** Only photos with texture in at least this share of their tiles are judged for blur: fog, sky and walls can't be. */
const val MIN_TEXTURED = 0.25f
/** Tiles whose brightness spreads (standard deviation, 0 to 255) less than this are plain and left out of the blur score. */
const val TEXTURE_SPREAD = 8f
/** Too dark: even the brightest 1% of the photo is below this (0 to 255). A pocket shot, a covered lens. */
const val DARK_HIGH = 40
/** Too bright: even the darkest 1% is above this. Washed out. */
const val BRIGHT_LOW = 215
const val OLD_SCREENSHOT_MS = 30 * 24 * 60 * 60 * 1000L
const val BIG_VIDEO_BYTES = 100_000_000L // 100 MB the way Android shows sizes (1,000-based)

/**
 * Clean up's categories, in chip order, which is also the order they claim items in: each item shows under the first one
 * that explains it (see [cleanupOf]). [suggested] ones make up "N photos to review" and can be selected in one tap; the
 * others are there to browse and pick from. [app], when set, lends its launcher icon in place of [sym].
 */
enum class CleanupKind(val label: String, val sym: Sym, val suggested: Boolean, val app: MediaApp? = null) {
    Copies("Copies", Sym.ContentCopy, true),
    Similar("Similar shots", Sym.BurstMode, true),
    Exposure("Too dark or bright", Sym.LightMode, true), // each tile says which
    Blurry("Blurry", Sym.Contrast, true),
    Screenshots("Old screenshots", Sym.ScreenshotMonitor, true),
    BigVideos("Big videos", Sym.Videocam, false),
    WhatsApp("WhatsApp", Sym.Folder, false, MediaApp.WhatsApp),
}

/** What cleanup reads of one item: a [Media] without its URI, so the rules run in plain unit tests. [modified] (seconds) is only known to the measuring pass. */
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
    val modified: Long = 0,
)

fun Media.toShot() = Shot(id, isVideo, taken, width, height, size, bucketName, path, name)

/** Copies of one photo, or shots of one moment. [ids] start with [keep]: the original, or the best shot. */
data class ShotGroup(val keep: Long, val ids: List<Long>)

/**
 * What each category holds, every item in one category at most. Groups for copies (exact ones and re-saved ones, around
 * the original) and similar shots; plain lists, newest first, for the rest.
 */
data class CleanupResult(
    val copies: List<ShotGroup>,
    val similar: List<ShotGroup>,
    val dark: List<Long>,
    val bright: List<Long>,
    val blurry: List<Long>,
    val screenshots: List<Long>,
    val bigVideos: List<Long>, // biggest first
    val whatsApp: List<Long>,
) {
    /** Originals of copies. A similar shot that has copies is one you shared or saved, so it stays like a best shot. */
    val originals: Set<Long> by lazy { copies.mapTo(HashSet()) { it.keep } }

    /** What [kind] suggests moving to Trash: every item, except what stays in each group. Browse categories: everything. */
    fun candidates(kind: CleanupKind): List<Long> = when (kind) {
        CleanupKind.Copies -> copies.flatMap { it.ids.drop(1) }
        CleanupKind.Similar -> similar.flatMap { g -> g.ids.drop(1).filter { it !in originals } }
        CleanupKind.Exposure -> dark + bright
        CleanupKind.Blurry -> blurry
        CleanupKind.Screenshots -> screenshots
        CleanupKind.BigVideos -> bigVideos
        CleanupKind.WhatsApp -> whatsApp
    }

    fun groups(kind: CleanupKind): List<ShotGroup> = when (kind) {
        CleanupKind.Copies -> copies
        CleanupKind.Similar -> similar
        else -> emptyList()
    }

    /**
     * The copies among [trashed], each as (original, copy), when their original stays: their favourites and collections
     * move there first, however they reach Trash. In [trashed]'s order.
     */
    fun originalsOf(trashed: Collection<Long>): List<Pair<Long, Long>> {
        val keepOf = HashMap<Long, Long>()
        for (g in copies) for (id in g.ids.drop(1)) keepOf[id] = g.keep
        val going = trashed.toHashSet()
        return trashed.mapNotNull { copy -> keepOf[copy]?.takeIf { it !in going }?.let { it to copy } }
    }
}

data class CleanupCount(val count: Int, val bytes: Long)

/** Per category, how many items it suggests and the space they'd free. [toReview] counts each suggested item once. */
data class CleanupSummary(val kinds: Map<CleanupKind, CleanupCount>, val toReview: Int, val reviewBytes: Long)

// ---------- Rules: pure, so they're unit tested ----------

/**
 * Sorts the Library into cleanup categories, each item under the first that explains it, in [CleanupKind] order: a copy
 * is a copy before it's an old screenshot or a WhatsApp photo, a similar shot before it's blurry. The photo that stays in
 * a group (an original, a best shot) is never suggested anywhere. Similar, blurry and too dark or bright only judge your
 * own shots (camera details, which apps strip when they share or save a picture): forwards, downloads and designs read as
 * alike or soft for other reasons, such as one template or flat colour. [rows] are what [Cleanup] measured, by id, for the
 * files as they are now; items not measured yet are only left out of the categories that need a measurement. [pairs] are
 * the copies it found. [now] is epoch millis.
 */
fun cleanupOf(shots: List<Shot>, rows: Map<Long, CleanupRow>, pairs: List<CopyPair>, now: Long): CleanupResult {
    val sizes = shots.associate { it.id to it.size }
    val exact = exactGroups(shots, rows)
    val exactCopies = exact.flatMapTo(HashSet()) { it.ids.drop(1) }
    val photos = shots.filter { !it.isVideo }
    val copies = mergeCopies(exact, copyGroups(photos.filter { it.id !in exactCopies }, rows, pairs)) { sizes[it] ?: 0L }
    val inCopies = copies.flatMapTo(HashSet()) { it.ids }
    val originals = copies.mapTo(HashSet()) { it.keep }
    val own = photos.filter { s ->
        rows[s.id]?.capture != null && !isScreenshot(s.path, s.name) && (s.id !in inCopies || s.id in originals)
    }
    val hashes = HashMap<Long, LongArray>()
    for (s in own) rows[s.id]?.takeIf { (it.quality ?: 0) >= MIN_QUALITY }?.pdq?.let { hashes[s.id] = pdqOf(it) }
    val similar = similarGroups(own, { hashes[it.id] }) { group -> bestShot(group, { it.id in originals }) { s -> rows[s.id] } }
    val grouped = inCopies + similar.flatMap { it.ids }
    val loose = own.filter { it.id !in grouped }.map { it.id to rows.getValue(it.id) }
    val dark = loose.filter { isDark(it.second) }.map { it.first }
    val bright = loose.filter { isBright(it.second) }.map { it.first }
    val blurry = loose.filter { (id, row) -> id !in dark && id !in bright && isBlurry(row) }.map { it.first }
    val screenshots = photos.filter { isScreenshot(it.path, it.name) && now - it.taken > OLD_SCREENSHOT_MS && it.id !in inCopies }.map { it.id }
    val bigVideos = shots.filter { it.isVideo && it.size > BIG_VIDEO_BYTES && it.id !in inCopies }.sortedByDescending { it.size }.map { it.id }
    val claimed = grouped + dark + bright + blurry + screenshots + bigVideos
    return CleanupResult(
        copies = copies,
        similar = similar,
        dark = dark,
        bright = bright,
        blurry = blurry,
        screenshots = screenshots,
        bigVideos = bigVideos,
        whatsApp = shots.filter { it.id !in claimed && storageKind(it.isVideo, it.path, it.bucket, it.name) == StorageKind.WhatsApp }.map { it.id },
    )
}

/**
 * Exact copies and copies as one list of groups, each around the original that stays: an exact group joins the copies
 * group its kept item is in (a WhatsApp photo saved twice is still a copy of the camera photo it came from), so every copy
 * shows once, next to its original. Groups that free the most ([size] per id) first.
 */
fun mergeCopies(exact: List<ShotGroup>, near: List<ShotGroup>, size: (Long) -> Long): List<ShotGroup> {
    val home = HashMap<Long, Int>()
    near.forEachIndexed { i, g -> g.ids.forEach { home[it] = i } }
    val joined = near.map { it.ids.toMutableList() }
    val alone = ArrayList<ShotGroup>()
    for (g in exact) home[g.keep]?.let { joined[it] += g.ids.drop(1) } ?: alone.add(g)
    return (near.mapIndexed { i, g -> ShotGroup(g.keep, joined[i]) } + alone).sortedByDescending { g -> g.ids.drop(1).sumOf(size) }
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

/**
 * Which of several copies is the original, to keep: not a screenshot (it has the screen's pixels, not the photo's), then
 * one with camera details (apps strip them when sharing, so they mark the capture itself, with its date and place), then
 * the most pixels, then one outside WhatsApp, Screenshots and Download, then the earliest taken, then the first saved
 * (MediaStore numbers files in the order it finds them). Camera details before pixels: an enlarged or "enhanced" export
 * has more pixels than the photo it came from, and must never get it trashed. Not the bigger file: with equal pixels
 * that's the higher JPEG quality or more metadata, which an edit saved as a copy can have too. Never "the sharpest":
 * recompression adds blocky edges that read as detail.
 */
fun originalOf(copies: List<Shot>, camera: (Shot) -> Boolean): Shot = copies.minWith(
    compareBy<Shot> { isScreenshot(it.path, it.name) }
        .thenByDescending { camera(it) }
        .thenByDescending { it.width.toLong() * it.height }
        .thenBy { secondHand(it) }
        .thenBy { it.taken }
        .thenBy { it.id },
)

// Where saved and shared copies land, rather than where the camera puts its photos
private fun secondHand(s: Shot) = s.path.startsWith("Download", ignoreCase = true) ||
    storageKind(s.isVideo, s.path, s.bucket, s.name).let { it == StorageKind.WhatsApp || it == StorageKind.Screenshots }

/**
 * Byte-for-byte copies, and copies with the same pixels but other metadata (a changed date, stripped camera details):
 * the same [CleanupRow.hash], and for photos the same size on screen and the same PDQ. Groups that free the most first.
 */
fun exactGroups(shots: List<Shot>, rows: Map<Long, CleanupRow>): List<ShotGroup> =
    shots.filter { !rows[it.id]?.hash.isNullOrEmpty() }
        .groupBy { s -> rows.getValue(s.id).let { listOf(it.hash, it.pdq, s.isVideo, s.width, s.height) } }.values
        .filter { it.size > 1 }
        .sortedByDescending { it[0].size * (it.size - 1) }
        .map { group -> groupAround(originalOf(group) { rows[it.id]?.capture != null }, group) }

/**
 * Copies across the whole library: photos whose PDQ hashes (turned or mirrored too) are at most [COPY_DISTANCE] apart, both
 * clear enough to compare ([MIN_QUALITY]), at least one without camera details (see [bothFromCamera]). Two screenshots
 * are never copies either, since one app's screens all look alike. Each group is an original ([originalOf]) and the photos
 * that are copies of it directly, so a chain of near misses never pulls in a photo that looks different from the one kept.
 */
fun copyGroups(photos: List<Shot>, rows: Map<Long, CleanupRow>, pairs: List<CopyPair>): List<ShotGroup> {
    val byId = photos.associateBy { it.id }
    fun clear(s: Shot) = (rows[s.id]?.quality ?: 0) >= MIN_QUALITY
    val links = pairs.filter { p ->
        val a = byId[p.a]
        val b = byId[p.b]
        a != null && b != null && p.distance <= COPY_DISTANCE && clear(a) && clear(b) &&
            !(isScreenshot(a.path, a.name) && isScreenshot(b.path, b.name)) && !bothFromCamera(rows[a.id], rows[b.id])
    }
    val parent = HashMap<Long, Long>()
    fun root(id: Long): Long {
        var r = id
        while (true) {
            val up = parent[r]
            if (up == null || up == r) return r
            r = up
        }
    }
    for (p in links) parent[root(p.b)] = root(p.a)
    val linked = links.flatMapTo(HashSet()) { listOf(it.a to it.b, it.b to it.a) }
    return links.flatMap { listOf(it.a, it.b) }.distinct().groupBy(::root).values
        .mapNotNull { ids ->
            val members = ids.map(byId::getValue)
            val original = originalOf(members) { rows[it.id]?.capture != null }
            members.filter { it === original || (original.id to it.id) in linked }.takeIf { it.size > 1 }?.let { groupAround(original, it) }
        }
        .sortedByDescending { g -> g.ids.drop(1).sumOf { byId.getValue(it).size } }
}

/**
 * Both have camera details, which apps strip when they share or save a copy: two shots taken at different moments (a
 * burst), or one moment and an edit of it saved as a copy, which was made on purpose. Neither is pre-selected as a copy;
 * close in time and look, they're similar shots, picked by hand.
 */
fun bothFromCamera(a: CleanupRow?, b: CleanupRow?) = a?.capture != null && b?.capture != null

// [keep] first, then the rest newest first
private fun groupAround(keep: Shot, group: List<Shot>) =
    ShotGroup(keep.id, listOf(keep.id) + group.filter { it !== keep }.sortedByDescending { it.taken }.map { it.id })

/**
 * Groups of shots of one moment: taken at most [SIMILAR_WINDOW_MS] from the shot the group grows around, the same way
 * up, at most [SIMILAR_DISTANCE] PDQ bits from every other shot in the group (complete linkage, so A like B and B like C
 * never puts A with C), the whole group within [SIMILAR_SPAN_MS]. Photos with no [hash] are left out. Each group is its
 * [best] shot first, then newest first; groups newest first.
 */
fun similarGroups(photos: List<Shot>, hash: (Shot) -> LongArray?, best: (List<Shot>) -> Shot): List<ShotGroup> {
    val items = photos.filter { hash(it) != null }.sortedBy { it.taken }
    val hashes = items.map { hash(it)!! }
    val near = Array(items.size) { ArrayList<Int>() }
    // ponytail: compares every pair inside the time window, O(n·k). A 300-shot burst is 45k comparisons of 4 longs; fine.
    for (i in items.indices) {
        var j = i + 1
        while (j < items.size && items[j].taken - items[i].taken <= SIMILAR_WINDOW_MS) {
            if (landscape(items[i]) == landscape(items[j]) && pdqDistance(hashes[i], hashes[j]) <= SIMILAR_DISTANCE) {
                near[i] += j
                near[j] += i
            }
            j++
        }
    }
    val grouped = BooleanArray(items.size)
    val groups = ArrayList<List<Shot>>()
    // Each group grows around its best-connected shot, its closest look-alikes first
    for (centre in items.indices.sortedByDescending { near[it].size }) {
        if (grouped[centre]) continue
        val group = arrayListOf(centre)
        var first = items[centre].taken
        var last = first
        for (c in near[centre].filter { !grouped[it] }.sortedBy { pdqDistance(hashes[centre], hashes[it]) }) {
            val t = items[c].taken
            if (maxOf(last, t) - minOf(first, t) > SIMILAR_SPAN_MS) continue
            if (group.any { pdqDistance(hashes[it], hashes[c]) > SIMILAR_DISTANCE }) continue
            group += c
            first = minOf(first, t)
            last = maxOf(last, t)
        }
        if (group.size < 2) continue
        group.forEach { grouped[it] = true }
        groups += group.map(items::get)
    }
    return groups.sortedByDescending { group -> group.maxOf { it.taken } }.map { groupAround(best(it), it) }
}

private fun landscape(s: Shot) = s.width > s.height

/**
 * The shot to keep from similar ones. First one that's [picked] already (shared or saved, so it has copies). When faces
 * were found: everyone's eyes open, then smiles, then the sharpest face. Then (or without faces) sharpness, as a share of
 * the sharpest shot's, less the share of blown or crushed pixels; then the most pixels; then the newest. Probabilities
 * count in thirds and sharpness in steps, since both are rough: a slightly higher number shouldn't decide.
 */
fun bestShot(shots: List<Shot>, picked: (Shot) -> Boolean = { false }, row: (Shot) -> CleanupRow?): Shot {
    val faces = shots.any { row(it)?.eyes != null }
    val sharpest = shots.maxOf { row(it)?.blur ?: 0f }.takeIf { it > 0f } ?: 1f
    return shots.maxWith(
        compareBy<Shot>(
            { picked(it) },
            { if (faces) third(row(it)?.eyes) else 0 },
            { if (faces) third(row(it)?.smile) else 0 },
            { if (faces) sharpStep(row(it)?.faceSharpness ?: 0f) else 0 },
            { floor(((row(it)?.blur ?: 0f) / sharpest - (row(it)?.clipped ?: 0f)) * 10).toInt() },
            { it.width.toLong() * it.height },
            { it.taken },
            { it.id },
        ),
    )
}

private fun third(p: Float?) = when {
    p == null -> -1 // no reading at all (no face, or one turned away) ranks lowest
    p >= 0.7f -> 2
    p >= 0.3f -> 1
    else -> 0
}

// Steps of about 15%, so a face barely sharper doesn't outrank one with open eyes on the next key
private fun sharpStep(sharpness: Float) = (ln(1.0 + sharpness.coerceAtLeast(0f) * 100) / ln(1.15)).toInt()

/** Soft enough for a closer look: texture enough to judge, a low score (lower for night shots), no depth effect. Faces decide ([isBlurry]). */
fun mayBeBlurry(row: CleanupRow): Boolean {
    val blur = row.blur ?: return false
    if ((row.textured ?: 0f) < MIN_TEXTURED || row.portrait == true) return false
    return blur < if (row.night == true) BLUR_SHARPNESS_NIGHT else BLUR_SHARPNESS
}

/** Blurry: [mayBeBlurry], and the closer look found no sharp face (a sharp face on a soft background is a portrait). */
fun isBlurry(row: CleanupRow) = mayBeBlurry(row) && row.faces != null && (row.faceSharpness ?: 0f) < FACE_SHARPNESS

fun isDark(row: CleanupRow) = row.high != null && row.high < DARK_HIGH

fun isBright(row: CleanupRow) = row.low != null && row.low > BRIGHT_LOW

/** Sizes two or more files share: only those files need a content hash. Size 0 means MediaStore doesn't know it. */
fun sharedSizes(sizes: List<Long>): Set<Long> = sizes.filter { it > 0 }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

/** Whether [row] still describes [item]: same file, measured by this version. */
fun CleanupRow.matches(item: Shot, version: Int) = modified == item.modified && size == item.size && this.version == version

/**
 * One file to measure: [look] decodes it (photos), [hash] reads all of it (another file may be the same picture),
 * [faces] looks for faces in it (it's in a group of similar shots, or may be blurry).
 */
class CleanupJob(val item: Shot, val look: Boolean, val hash: Boolean, val faces: Boolean)

class CleanupPlan(val work: List<CleanupJob>, val remove: List<Long>)

/**
 * What to measure, in [found]'s order: new and edited photos; a content hash for files that share their size with another
 * file, or (photos) their size on screen and PDQ with another photo; faces for similar shots and blur suspects. Plus which
 * rows belong to media that's gone. Hashes and faces depend on measurements, so a pass plans again until nothing is left.
 */
fun planCleanup(found: List<Shot>, rows: List<CleanupRow>, pairs: List<CopyPair>, version: Int, now: Long): CleanupPlan {
    val known = rows.associateBy { it.id }
    val valid = HashMap<Long, CleanupRow>()
    for (item in found) known[item.id]?.takeIf { it.matches(item, version) }?.let { valid[item.id] = it }
    val sizes = sharedSizes(found.map { it.size })
    fun look(s: Shot, row: CleanupRow) = row.pdq?.let { "$it ${s.width}x${s.height}" }
    val looks = found.mapNotNull { s -> valid[s.id]?.let { look(s, it) } }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val closer = cleanupOf(found, valid, pairs, now).similar.flatMapTo(HashSet()) { it.ids }
    // Only your own shots are judged for blur (see cleanupOf), so only theirs need the closer look
    found.filter { !it.isVideo && !isScreenshot(it.path, it.name) && valid[it.id]?.let { r -> r.capture != null && mayBeBlurry(r) } == true }.mapTo(closer) { it.id }
    val work = ArrayList<CleanupJob>()
    val remove = ArrayList<Long>()
    for (item in found) {
        val row = valid[item.id]
        val look = !item.isVideo && row == null
        val hash = row?.hash == null && (item.size in sizes || row?.let { look(item, it) } in looks)
        val faces = row != null && row.faces == null && item.id in closer
        when {
            look || hash || faces -> work += CleanupJob(item, look, hash, faces)
            row == null && item.id in known -> remove += item.id // an edited video that no longer needs a hash
        }
    }
    val present = found.mapTo(HashSet(found.size)) { it.id }
    rows.filter { it.id !in present }.mapTo(remove) { it.id }
    return CleanupPlan(work, remove)
}

// ---------- Measuring: pure, on pixels and bytes ----------

/** What [lookAt] measures of one photo; [CleanupRow] says what each value means. */
class PhotoLook(val pdq: Pdq, val blur: Float?, val textured: Float, val low: Int, val high: Int, val clipped: Float)

// Screenshots lose this share of their height at the top (status bar) and the bottom (navigation bar), so a screenshot of
// a photo compares with the photo, whatever the time and battery said
private const val STATUS_BAR = 0.05f
private const val NAVIGATION_BAR = 0.07f

/**
 * Measures a photo from its pixels: [argb], [width] by [height], upright and at most [LOOK_PX] on the long side. A frame
 * of one colour (letterboxing, a white border) is left out first, so a copy with or without one measures the same.
 */
fun lookAt(argb: IntArray, width: Int, height: Int, screenshot: Boolean, hasher: PdqHasher): PhotoLook {
    val top = if (screenshot) (height * STATUS_BAR).toInt() else 0
    val rows = height - top - if (screenshot) (height * NAVIGATION_BAR).toInt() else 0
    val luma = FloatArray(width * rows) { pdqLuma(argb[top * width + it]) }
    val (l, t, r, b) = frameOf(luma, width, rows)
    val w = r - l
    val h = b - t
    val inner = if (w == width && h == rows) luma else FloatArray(w * h) { luma[(t + it / w) * width + l + it % w] }
    val (blur, textured) = sharpnessOf(inner, w, h)
    val histogram = IntArray(256)
    for (v in inner) histogram[v.toInt().coerceIn(0, 255)]++
    val clipped = (0..4).sumOf { histogram[it] } + (251..255).sumOf { histogram[it] }
    // PDQ reads a photo the same at any size (Meta: 256 px almost always matches), so it gets a quarter of the pixels
    val pdq = if (maxOf(w, h) > LOOK_PX / 2) {
        val hw = w / 2
        val hh = h / 2
        hasher.hash(FloatArray(hw * hh) { i -> (i / hw * 2 * w + i % hw * 2).let { (inner[it] + inner[it + 1] + inner[it + w] + inner[it + w + 1]) / 4 } }, hw, hh)
    } else {
        hasher.hash(inner, w, h)
    }
    return PhotoLook(pdq, blur, textured, percentile(histogram, 0.01f), percentile(histogram, 0.99f), clipped.toFloat() / inner.size)
}

// The brightness below which [share] of the pixels fall
private fun percentile(histogram: IntArray, share: Float): Int {
    val target = ceil(histogram.sum() * share).toInt().coerceAtLeast(1)
    var seen = 0
    for ((level, n) in histogram.withIndex()) {
        seen += n
        if (seen >= target) return level
    }
    return 255
}

// A frame is near black or near white (letterboxing, a border added by an app), at most this share of each side (a
// landscape photo in a portrait screenshot has over a third above and below it), and its pixels stray at most
// FRAME_TOLERANCE from its colour (JPEG ripples along the picture's edge)
private const val MAX_FRAME = 0.4f
private const val FRAME_TOLERANCE = 10f

/** The picture inside any plain black or white frame of [luma] ([w] by [h]), as left, top, right and bottom (exclusive). */
fun frameOf(luma: FloatArray, w: Int, h: Int): IntArray {
    fun plain(colour: Float, from: Int, count: Int, step: Int) = (0 until count).all { abs(luma[from + it * step] - colour) <= FRAME_TOLERANCE }
    fun frame(colour: Float) = colour <= 24f || colour >= 231f
    var top = 0
    val topColour = luma[0]
    while (frame(topColour) && top < h * MAX_FRAME && plain(topColour, top * w, w, 1)) top++
    var bottom = h
    val bottomColour = luma[(h - 1) * w]
    while (frame(bottomColour) && h - bottom < h * MAX_FRAME && plain(bottomColour, (bottom - 1) * w, w, 1)) bottom--
    var left = 0
    val leftColour = luma[top * w]
    while (frame(leftColour) && left < w * MAX_FRAME && plain(leftColour, top * w + left, bottom - top, w)) left++
    var right = w
    val rightColour = luma[top * w + w - 1]
    while (frame(rightColour) && w - right < w * MAX_FRAME && plain(rightColour, top * w + right - 1, bottom - top, w)) right--
    return intArrayOf(left, top, right, bottom)
}

/**
 * Sharpness over an 8 x 8 grid of tiles of [luma] ([w] by [h]): see [CleanupRow.blur] and [CleanupRow.textured]. Each
 * textured tile scores [crispness]; the photo scores the [SHARPEST_TILES] percentile, so a sharp subject on a soft
 * background is sharp.
 */
fun sharpnessOf(luma: FloatArray, w: Int, h: Int): Pair<Float?, Float> {
    if (w < 16 || h < 16) return null to 0f
    val log = logOf(luma, w, h)
    val scores = ArrayList<Float>(64)
    for (ty in 0 until 8) for (tx in 0 until 8) {
        crispness(luma, log, w, tx * w / 8, ty * h / 8, (tx + 1) * w / 8, (ty + 1) * h / 8)?.let { scores += it }
    }
    if (scores.isEmpty()) return null to 0f
    scores.sort()
    return scores[ceil(scores.size * SHARPEST_TILES).toInt() - 1] to scores.size / 64f
}

/**
 * How crisp the edges inside [left, top, right, bottom) are for their contrast: var(LoG) / var(brightness), from [luma]
 * and its [log] ([w] wide). Contrast cancels out, so a dim sharp tile beats a bright soft one. Null for a plain area,
 * where blur can't be told from a smooth surface.
 */
fun crispness(luma: FloatArray, log: FloatArray, w: Int, left: Int, top: Int, right: Int, bottom: Int): Float? {
    var n = 0
    var sum = 0.0
    var squares = 0.0
    var logSum = 0.0
    var logSquares = 0.0
    for (y in top until bottom) for (x in left until right) {
        val v = luma[y * w + x].toDouble()
        val l = log[y * w + x].toDouble()
        n++
        sum += v
        squares += v * v
        logSum += l
        logSquares += l * l
    }
    if (n < 16) return null
    val variance = squares / n - (sum / n) * (sum / n)
    if (variance < TEXTURE_SPREAD * TEXTURE_SPREAD) return null
    return ((logSquares / n - (logSum / n) * (logSum / n)) / variance).toFloat()
}

/**
 * Laplacian of [luma] ([w] by [h]) after a Gaussian blur of sigma 1 (binomial 1 4 6 4 1, whose sigma is exactly 1): edges
 * stand out, sensor noise and JPEG blocks don't. 0 on the outermost pixels.
 */
fun logOf(luma: FloatArray, w: Int, h: Int): FloatArray {
    // Pixels past the edge repeat the edge; inside, plain sums, the hot loop of measuring
    fun smooth(v: FloatArray, at: Int, step: Int, i: Int, n: Int): Float =
        if (i in 2 until n - 2) (v[at - 2 * step] + 4 * v[at - step] + 6 * v[at] + 4 * v[at + step] + v[at + 2 * step]) / 16
        else (v[at + (maxOf(i - 2, 0) - i) * step] + 4 * v[at + (maxOf(i - 1, 0) - i) * step] + 6 * v[at] +
            4 * v[at + (minOf(i + 1, n - 1) - i) * step] + v[at + (minOf(i + 2, n - 1) - i) * step]) / 16
    val across = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) across[y * w + x] = smooth(luma, y * w + x, 1, x, w)
    val blurred = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) blurred[y * w + x] = smooth(across, y * w + x, w, y, h)
    val log = FloatArray(w * h)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        log[i] = blurred[i - 1] + blurred[i + 1] + blurred[i - w] + blurred[i + w] - 4 * blurred[i]
    }
    return log
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
    return hex(digest.digest())
}

/**
 * SHA-256 of a picture file's image data without its metadata, so a copy whose date, camera details or editing app
 * changed still matches its original: JPEG without its APPn segments (EXIF, XMP, colour profile) and comments, up to the
 * end of the image (a motion photo's video after it is left out); PNG without text, time, EXIF and density chunks. Other
 * formats hash whole.
 */
fun imageHash(file: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val jpeg = file.size > 4 && file[0] == 0xFF.toByte() && file[1] == 0xD8.toByte()
    val png = file.size > 8 && file.copyOf(8).contentEquals(PNG_SIGNATURE)
    when {
        jpeg -> jpegImage(file, digest)
        png -> pngImage(file, digest)
        else -> digest.update(file)
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun jpegImage(f: ByteArray, digest: MessageDigest) {
    digest.update(f, 0, 2) // start of image
    var i = 2
    while (i + 1 < f.size) {
        if (f[i] != 0xFF.toByte()) break // not a marker where one should be: hash the rest as it is
        val marker = f[i + 1].toInt() and 0xFF
        when {
            marker == 0xFF -> { i++; continue } // fill byte before a marker
            marker == 0xD9 -> { digest.update(f, i, 2); return } // end of image: anything after (a motion photo's video) is extra
            marker in 0xD0..0xD7 || marker == 0x01 -> { digest.update(f, i, 2); i += 2; continue } // markers without a length
        }
        if (i + 3 >= f.size) break
        val end = minOf(f.size, i + 2 + ((f[i + 2].toInt() and 0xFF) shl 8 or (f[i + 3].toInt() and 0xFF)))
        if (marker !in 0xE0..0xEF && marker != 0xFE) digest.update(f, i, end - i)
        i = end
        if (marker == 0xDA) { // start of scan: the compressed pixels run to the next marker that isn't a restart or a stuffed 0xFF
            var j = i
            while (j + 1 < f.size && !(f[j] == 0xFF.toByte() && (f[j + 1].toInt() and 0xFF).let { it != 0 && it !in 0xD0..0xD7 })) j++
            digest.update(f, i, j - i)
            i = j
        }
    }
    if (i < f.size) digest.update(f, i, f.size - i)
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val PNG_METADATA = setOf("tEXt", "zTXt", "iTXt", "tIME", "eXIf", "pHYs")

private fun pngImage(f: ByteArray, digest: MessageDigest) {
    digest.update(f, 0, 8)
    var i = 8
    while (i + 12 <= f.size) {
        val length = (f[i].toInt() and 0xFF shl 24) or (f[i + 1].toInt() and 0xFF shl 16) or (f[i + 2].toInt() and 0xFF shl 8) or (f[i + 3].toInt() and 0xFF)
        if (length < 0) break
        val type = String(f, i + 4, 4, Charsets.ISO_8859_1)
        val end = minOf(f.size.toLong(), i + 12L + length).toInt()
        if (type !in PNG_METADATA) digest.update(f, i, end - i)
        i = end
        if (type == "IEND") return
    }
    if (i < f.size) digest.update(f, i, f.size - i)
}

/**
 * Every measured photo's PDQ that's clear enough to match, for finding a new photo's copies anywhere in the library.
 * Multi-index hashing (Norouzi et al. 2014, as Meta's MIH256 does it): the 256 bits are cut into 16 slots of 16 bits. Two
 * hashes at most [COPY_DISTANCE] (31) bits apart agree in some slot to within one bit, since 16 slots with two or more
 * would make 32, so a search looks up each slot's value and its 16 one-bit neighbours, in all eight turns, and counts
 * every bit only for the few photos found there. On a laptop a first pass over 20,000 photos searches in 0.2 s and over
 * 100,000 in 7 s (CleanupTest), where comparing every pair took 18 s for 20,000.
 */
class CopyIndex {
    private var ids = LongArray(1024)
    private var hashes = LongArray(1024 * PDQ_LONGS)
    private var next = IntArray(1024 * SLOTS) // per photo and slot: the photo added before it with the same slot value, or -1
    private val heads = IntArray(SLOTS shl 16) { -1 } // per slot and value: the photo added last with it, or -1
    var size = 0
        private set

    /** Adds photo [id]'s hash, the first [PDQ_LONGS] longs of [hash]. */
    fun add(id: Long, hash: LongArray) {
        if (size == ids.size) {
            ids = ids.copyOf(size * 2)
            hashes = hashes.copyOf(size * 2 * PDQ_LONGS)
            next = next.copyOf(size * 2 * SLOTS)
        }
        ids[size] = id
        hash.copyInto(hashes, size * PDQ_LONGS, 0, PDQ_LONGS)
        for (s in 0 until SLOTS) {
            val key = (s shl 16) or slot(hash, 0, s)
            next[size * SLOTS + s] = heads[key]
            heads[key] = size
        }
        size++
    }

    /** Photos in the index within [COPY_DISTANCE] of photo [id], whose [Pdq.hashes] are [turns], by their closest turn. */
    fun copiesOf(id: Long, turns: LongArray): List<CopyPair> {
        val closest = HashMap<Int, Int>() // place in the index to distance
        for (turn in 0 until turns.size / PDQ_LONGS) {
            val at = turn * PDQ_LONGS
            for (s in 0 until SLOTS) {
                val value = slot(turns, at, s)
                for (flip in -1 until 16) {
                    var k = heads[(s shl 16) or if (flip < 0) value else value xor (1 shl flip)]
                    while (k >= 0) {
                        val d = pdqDistance(turns, at, hashes, k * PDQ_LONGS)
                        if (d <= COPY_DISTANCE) closest.merge(k, d, ::minOf)
                        k = next[k * SLOTS + s]
                    }
                }
            }
        }
        return closest.mapNotNull { (k, d) -> if (ids[k] == id) null else CopyPair(minOf(id, ids[k]), maxOf(id, ids[k]), d) }
    }

    private companion object {
        const val SLOTS = 16

        // Bits 16s to 16s + 15 of the hash at [at]
        fun slot(hash: LongArray, at: Int, s: Int) = (hash[at + s / 4] ushr (16 * (s % 4))).toInt() and 0xFFFF
    }
}

// ---------- Detection ----------

/**
 * Finds what can go: exact copies, copies across the library, similar shots (with the best one picked), blurry, too dark
 * or too bright photos, old screenshots, WhatsApp media and big videos. Each file is measured once (decoded at [LOOK_PX],
 * its content hashed only when another file may be the same picture, faces looked for only when they decide something)
 * and kept in its own database, so later passes only measure what's new. Nothing leaves the phone and nothing is deleted
 * here: the cleanup screen asks.
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

    // One background-priority thread for decoding, hashing and ML Kit, so the UI thread always wins the CPU
    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "cleanup")
    }
    private val background = thread.asCoroutineDispatcher()

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
        // Categories come from the Library as it is now plus what's measured so far, so trashed photos leave at once.
        // Hidden items stay out of them (and out of For you's counts); they're still measured, so unhiding needs no pass.
        scope.launch {
            combine(Gallery.media.filterNotNull(), Hidden.state.filterNotNull(), passes) { media, hidden, _ -> media.withoutHidden(hidden) }.collectLatest { media ->
                val shots = media.map { it.toShot() }
                val sizes = shots.associate { it.id to it.size }
                // Rows of an older version or an edited file are as good as unmeasured until the pass redoes them
                val rows = dao.all().filter { it.version == CLEANUP_VERSION && it.size == sizes[it.id] }.associateBy { it.id }
                val result = cleanupOf(shots, rows, dao.pairs(), System.currentTimeMillis())
                _result.value = result
                _summary.value = summaryOf(result) { sizes[it] ?: 0L }
            }
        }
    }

    /** One pass: drops rows of deleted media, then measures new and changed files in batches, newest first, and plans again. */
    internal suspend fun run(onLongPass: suspend () -> Unit = {}) = withContext(background) {
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext
        // With "Select photos" access MediaStore lists only the picked ones: never treat the rest as deleted
        val partial = !has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO)
        val hasher = PdqHasher()
        var detector: FaceDetector? = null // made on first need: most passes after the first never need it
        var measured = 0
        val started = SystemClock.elapsedRealtime()
        try {
            do {
                // Never act on a failed or empty query: either would wipe the measurements
                val found = query()?.takeIf { it.isNotEmpty() } ?: return@withContext
                val rows = dao.all()
                val plan = planCleanup(found, rows, dao.pairs(), CLEANUP_VERSION, System.currentTimeMillis())
                if (!partial && plan.remove.isNotEmpty()) dao.remove(plan.remove)
                val known = rows.associateBy { it.id }
                // Each new photo is compared with every photo measured before it, so each pair of copies is found once, by the later one
                val index = CopyIndex()
                for (item in found) {
                    known[item.id]?.takeIf { it.matches(item, CLEANUP_VERSION) && (it.quality ?: 0) >= MIN_QUALITY }?.pdq?.let { index.add(item.id, pdqOf(it)) }
                }
                // Counted over the whole library, so a pass that resumes shows where it is, not a fresh start from 0
                var done = found.size - plan.work.size
                IndexStatus.report(JOB, done, found.size)
                for ((i, batch) in plan.work.chunked(BATCH).withIndex()) {
                    if (plan.work.size - i * BATCH >= LONG_PASS) onLongPass()
                    val out = batch.map { measure(it, known[it.item.id], hasher, index) { detector ?: faceDetector().also { d -> detector = d } } }
                    dao.save(out.map { it.row }, batch.filter { it.look }.map { it.item.id }, out.flatMap { it.pairs })
                    done += batch.size
                    IndexStatus.report(JOB, done, found.size)
                }
                measured += plan.work.size
                if (plan.work.isNotEmpty()) passes.update { it + 1 }
            } while (plan.work.isNotEmpty()) // photos taken during the pass, and hashes and faces it turns out to need, before it ends
        } finally {
            detector?.close()
            IndexStatus.report(JOB, 0, 0)
        }
        if (measured > 0) CrashLog.note("cleanup: $measured items, ${(SystemClock.elapsedRealtime() - started) / measured} ms each")
    }

    private fun has(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Null if MediaStore didn't answer. */
    private fun query(): List<Shot>? {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_MODIFIED, FileColumns.SIZE, FileColumns.DATE_TAKEN,
            FileColumns.WIDTH, FileColumns.HEIGHT, FileColumns.ORIENTATION, FileColumns.BUCKET_DISPLAY_NAME,
            FileColumns.RELATIVE_PATH, FileColumns.DISPLAY_NAME,
        )
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return app.contentResolver.query(uri, projection, PHOTOS_AND_VIDEOS, null, "${FileColumns._ID} DESC")?.use { c ->
            List(c.count) {
                c.moveToNext()
                val turned = c.getInt(7) % 180 == 90 // stored sideways with a rotation tag, as Gallery reads it
                Shot(
                    id = c.getLong(0),
                    isVideo = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO,
                    taken = c.getLong(4).takeIf { it > 0 } ?: (c.getLong(2) * 1000),
                    width = if (turned) c.getInt(6) else c.getInt(5),
                    height = if (turned) c.getInt(5) else c.getInt(6),
                    size = c.getLong(3),
                    bucket = c.getString(8) ?: "Other",
                    path = c.getString(9).orEmpty(),
                    name = c.getString(10).orEmpty(),
                    modified = c.getLong(2),
                )
            }
        }
    }

    private class Measured(val row: CleanupRow, val pairs: List<CopyPair>)

    /**
     * Measures one file on top of what's still valid in [old]. A file that can't be read is saved with nothing measured
     * (and an empty hash), so it's tried again only once it changes, instead of on every pass.
     */
    private suspend fun measure(job: CleanupJob, old: CleanupRow?, hasher: PdqHasher, index: CopyIndex, detector: () -> FaceDetector): Measured {
        val item = job.item
        val uri = mediaUri(item.id, item.isVideo)
        var row = old?.takeIf { it.matches(item, CLEANUP_VERSION) } ?: CleanupRow(item.id, item.modified, item.size, CLEANUP_VERSION)
        var pairs = emptyList<CopyPair>()
        if (job.look) {
            try {
                val look = decode(uri)?.let { lookAt(it, isScreenshot(item.path, item.name), hasher) }
                if (look != null) {
                    row = row.copy(
                        pdq = pdqHex(look.pdq.hashes), quality = look.pdq.quality, blur = look.blur, textured = look.textured,
                        low = look.low, high = look.high, clipped = look.clipped,
                    )
                    row = camera(uri, row)
                    if (look.pdq.quality >= MIN_QUALITY) {
                        pairs = index.copiesOf(item.id, look.pdq.hashes)
                        index.add(item.id, look.pdq.hashes)
                    }
                }
            } catch (e: Throwable) {
                CrashLog.note("cleanup: skipped a photo: $e") // corrupt file, or out of memory on a huge one
            }
        }
        if (job.hash) row = row.copy(hash = hashOf(uri, item.isVideo) ?: "")
        if (job.faces) {
            row = try {
                closer(uri, row, detector())
            } catch (e: CancellationException) {
                throw e // the pass is being stopped: nothing of this batch is saved
            } catch (e: Throwable) {
                CrashLog.note("cleanup: no faces: $e") // as for the look: corrupt, or out of memory
                row.copy(faces = 0)
            }
        }
        return Measured(row, pairs)
    }

    // Upright (ImageDecoder turns photos by their EXIF orientation), at most LOOK_PX on the long side, in software sRGB
    // ARGB_8888 so its pixels can be read, as Faces.decode does. Null when it can't be read.
    private fun decode(uri: Uri): Bitmap? = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val scale = LOOK_PX.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        // 10-bit HDR photos decode to other configs
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    } catch (e: Exception) {
        null // corrupt, vanished or unsupported
    }

    private fun lookAt(bitmap: Bitmap, screenshot: Boolean, hasher: PdqHasher): PhotoLook {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        bitmap.recycle()
        return lookAt(pixels, w, h, screenshot, hasher)
    }

    /**
     * Camera details from EXIF: when it was taken (two shots, not two copies, if both have it and it differs), whether it
     * was a long or high-ISO exposure (soft by nature), and whether it holds depth data (portrait mode blurs on purpose).
     */
    private fun camera(uri: Uri, row: CleanupRow): CleanupRow = try {
        app.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            val exif = ExifInterface(fd.fileDescriptor)
            val camera = exif.getAttribute(ExifInterface.TAG_MAKE) != null || exif.getAttribute(ExifInterface.TAG_MODEL) != null
            val taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf { camera }
            val capture = taken?.let { "$it.${exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL).orEmpty()}" }
            val night = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0) >= 1 / 15.0 ||
                exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0) >= 1600
            row.copy(capture = capture, night = night, portrait = hasDepth(uri))
        } ?: row
    } catch (e: Exception) {
        row // no readable EXIF: measured as a photo without camera details
    }

    // Depth data's marks near the start of the file: Google's Dynamic Depth container and older GDepth, and the depth and
    // portrait-matte images of Apple's HEIF photos. Samsung keeps its depth map at the end, so its portraits aren't told apart.
    private fun hasDepth(uri: Uri): Boolean {
        val file = app.contentResolver.openFileDescriptor(uri, "r") ?: return false
        val head = ParcelFileDescriptor.AutoCloseInputStream(file).use { it.readNBytes(64 * 1024) }
        return DEPTH.containsMatchIn(String(head, Charsets.ISO_8859_1)) // one char per byte: binary data can't break the decode
    }

    private val DEPTH = Regex("""GDepth:|Semantic="Depth"|:Semantic>Depth<|aux:depth|aux:disparity|portraiteffectsmatte""")

    // Photos: without metadata, read whole, which is fine at photo sizes; bigger files and videos byte for byte, streamed
    private fun hashOf(uri: Uri, isVideo: Boolean): String? = try {
        app.contentResolver.openFileDescriptor(uri, "r")?.let { fd ->
            val whole = isVideo || fd.statSize > 64 * 1024 * 1024
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { if (whole) contentHash(it) else imageHash(it.readBytes()) }
        }
    } catch (e: Exception) {
        null // vanished or unreadable
    }

    private fun faceDetector() = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eyes open, smiling
            .setMinFaceSize(0.08f)
            .setExecutor(thread)
            .build(),
    )

    /** Faces in the photo, for the best shot of similar ones and for telling a portrait from a blurry photo. */
    private suspend fun closer(uri: Uri, row: CleanupRow, detector: FaceDetector): CleanupRow {
        val bitmap = decode(uri) ?: return row.copy(faces = 0)
        try {
            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await() ?: return row.copy(faces = 0)
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val luma = FloatArray(w * h) { pdqLuma(pixels[it]) }
            val log = logOf(luma, w, h)
            return row.copy(
                faces = faces.size,
                eyes = faces.mapNotNull { f -> listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).minOrNull() }.minOrNull(),
                smile = faces.mapNotNull { it.smilingProbability }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                faceSharpness = faces.mapNotNull { f ->
                    val b = f.boundingBox
                    crispness(luma, log, w, b.left.coerceIn(1, w - 1), b.top.coerceIn(1, h - 1), b.right.coerceIn(1, w - 1), b.bottom.coerceIn(1, h - 1))
                }.maxOrNull(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    // ML Kit's Task as a suspend call; null when it fails.
    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { c ->
        addOnCompleteListener(thread) { c.resume(if (it.isSuccessful) it.result else null) }
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
