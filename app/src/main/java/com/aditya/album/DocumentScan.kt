package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Straighten document: for a photo of a page (a document, a receipt, a whiteboard) Album guesses where the page's four
// corners are, the person drags them where they belong, and Save makes a flat, straight copy of just the page, cleaned
// up if they like. Album's own code finds the page (no model, no library); Android's Matrix.setPolyToPoly straightens it.
// The original is only ever read; the copy goes next to it, as a rotated copy does.

// ---------- Finding the page ----------

/** The page is looked for on the photo at most this many pixels on its longest side: plenty to find its edges. */
private const val GUESS_PX = 256

/**
 * Where a page's corners are in a photo, guessed from [lum], its brightness (0 to 255) row by row, [width] x [height]:
 * paper is usually the biggest bright patch, and the page is the four-sided shape that covers most of that patch.
 * Corners are fractions of the width and height, top left, top right, bottom right, bottom left; null when no patch is
 * big enough to be a page.
 */
fun guessPage(lum: IntArray, width: Int, height: Int): List<Offset>? {
    if (width < 8 || height < 8) return null
    val smooth = boxBlur(lum, width, height) // so specks of dust and the gaps in print don't split the paper up
    val threshold = otsu(smooth)
    // Paper must stand out from what's round it; a photo of one even colour has no page to find
    val (dark, bright) = smooth.partition { it <= threshold }
    if (dark.isEmpty() || bright.isEmpty() || bright.average() - dark.average() < 40) return null
    val (outline, area) = biggestPatch(BooleanArray(width * height) { smooth[it] > threshold }, width, height) ?: return null
    if (area < width * height / 10) return null
    val hull = simplify(convexHull(outline), keep = 16)
    if (hull.size < 4) return null
    val quad = biggestQuad(hull)
    if (polygonArea(quad) < width * height / 10f) return null
    return ordered(quad).map { Offset(it.x / width, it.y / height) }
}

/** Each pixel averaged with its 8 neighbours; the edges repeat outwards. */
private fun boxBlur(values: IntArray, width: Int, height: Int) = IntArray(values.size) { i ->
    val x = i % width
    val y = i / width
    var sum = 0
    for (dy in -1..1) for (dx in -1..1) sum += values[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
    sum / 9
}

/** Otsu's threshold: the brightness that best splits [values] into a dark and a bright group. */
fun otsu(values: IntArray): Int {
    val histogram = IntArray(256)
    values.forEach { histogram[it.coerceIn(0, 255)]++ }
    val total = values.size
    val sum = (0..255).sumOf { it.toDouble() * histogram[it] }
    var darkSum = 0.0
    var dark = 0
    var best = -1.0
    var threshold = 127
    for (t in 0..255) {
        dark += histogram[t]
        if (dark == 0) continue
        val bright = total - dark
        if (bright == 0) break
        darkSum += t.toDouble() * histogram[t]
        val gap = darkSum / dark - (sum - darkSum) / bright
        val between = dark.toDouble() * bright * gap * gap
        if (between > best) {
            best = between
            threshold = t
        }
    }
    return threshold
}

/**
 * The biggest patch of touching [bright] pixels: its outline (the outer corners of its first and last pixel on each row,
 * which have the same convex hull as the whole patch) and its size in pixels. Null when nothing is bright.
 */
private fun biggestPatch(bright: BooleanArray, width: Int, height: Int): Pair<List<Offset>, Int>? {
    val patch = IntArray(bright.size) // which patch each pixel is in, 0 for none yet
    val queue = IntArray(bright.size)
    var biggest = 0
    var biggestSize = 0
    var count = 0
    for (start in bright.indices) {
        if (!bright[start] || patch[start] != 0) continue
        count++
        var head = 0
        var tail = 0
        queue[tail++] = start
        patch[start] = count
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < width - 1) i + 1 else -1, i - width, i + width)) {
                if (j in bright.indices && bright[j] && patch[j] == 0) {
                    patch[j] = count
                    queue[tail++] = j
                }
            }
        }
        if (tail > biggestSize) {
            biggestSize = tail
            biggest = count
        }
    }
    if (biggest == 0) return null
    val outline = ArrayList<Offset>()
    for (y in 0 until height) {
        var first = -1
        var last = -1
        for (x in 0 until width) if (patch[y * width + x] == biggest) {
            if (first < 0) first = x
            last = x
        }
        if (first < 0) continue
        outline += listOf(Offset(first.toFloat(), y.toFloat()), Offset(first.toFloat(), y + 1f), Offset(last + 1f, y.toFloat()), Offset(last + 1f, y + 1f))
    }
    return outline to biggestSize
}

/** > 0 when o, a, b turn clockwise on screen (y down), < 0 anticlockwise, 0 in a line. */
private fun turn(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

/** The smallest convex polygon around [points] (Andrew's monotone chain), corners in order. */
fun convexHull(points: List<Offset>): List<Offset> {
    val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size < 3) return sorted
    fun half(pts: List<Offset>): List<Offset> {
        val chain = ArrayList<Offset>()
        for (p in pts) {
            while (chain.size >= 2 && turn(chain[chain.size - 2], chain[chain.size - 1], p) <= 0) chain.removeAt(chain.lastIndex)
            chain += p
        }
        return chain.dropLast(1)
    }
    return half(sorted) + half(sorted.asReversed())
}

/** [polygon] (convex) with its least important corners dropped, the ones that cut off the least area, until [keep] are left. */
private fun simplify(polygon: List<Offset>, keep: Int): List<Offset> {
    val corners = polygon.toMutableList()
    while (corners.size > keep) {
        val n = corners.size
        val least = corners.indices.minBy { i -> abs(turn(corners[(i + n - 1) % n], corners[i], corners[(i + 1) % n])) }
        corners.removeAt(least)
    }
    return corners
}

/** The four of [polygon]'s corners (convex, in order) that enclose the most area. ponytail: tries them all, so keep it small. */
private fun biggestQuad(polygon: List<Offset>): List<Offset> {
    val n = polygon.size
    var best = polygon.take(4)
    var bestArea = -1f
    for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
        val quad = listOf(polygon[a], polygon[b], polygon[c], polygon[d])
        val area = polygonArea(quad)
        if (area > bestArea) {
            bestArea = area
            best = quad
        }
    }
    return best
}

/** Area inside [polygon], its corners in order either way round. */
fun polygonArea(polygon: List<Offset>): Float =
    abs(polygon.indices.sumOf { i -> polygon[i].let { p -> polygon[(i + 1) % polygon.size].let { q -> (p.x * q.y - q.x * p.y).toDouble() } } }.toFloat()) / 2

/** Four [points] as top left, top right, bottom right, bottom left: clockwise round their middle, from the one nearest the top left. */
fun ordered(points: List<Offset>): List<Offset> {
    val middle = Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
    val round = points.sortedBy { atan2(it.y - middle.y, it.x - middle.x) } // y down, so a growing angle is clockwise
    val first = round.indices.minBy { round[it].x + round[it].y }
    return List(4) { round[(first + it) % 4] }
}

/** Whether [corners], in order round the page, make a page shape: every corner turning the same way, none crossed over. */
fun isPageShape(corners: List<Offset>): Boolean {
    if (corners.size != 4) return false
    val turns = corners.indices.map { turn(corners[it], corners[(it + 1) % 4], corners[(it + 2) % 4]) }
    return turns.all { it > 0 } || turns.all { it < 0 }
}

/** Width and height of the straightened page in pixels, from its [corners] in pixels: the longer of each pair of opposite sides. */
fun pageSize(corners: List<Offset>): IntSize {
    val (topLeft, topRight, bottomRight, bottomLeft) = corners
    val width = max((topRight - topLeft).getDistance(), (bottomRight - bottomLeft).getDistance())
    val height = max((bottomLeft - topLeft).getDistance(), (bottomRight - topRight).getDistance())
    return IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
}

/** [size] made smaller, keeping its shape, until it has at most [maxPixels]. */
fun fitPixels(size: IntSize, maxPixels: Long): IntSize {
    val pixels = size.width.toLong() * size.height
    if (pixels <= maxPixels) return size
    val scale = sqrt(maxPixels.toDouble() / pixels)
    return IntSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
}

/** Where a page's corners start when Album can't find it: a little in from the photo's edges, so each is easy to grab. */
private val INSET = listOf(Offset(0.08f, 0.08f), Offset(0.92f, 0.08f), Offset(0.92f, 0.92f), Offset(0.08f, 0.92f))

// ---------- Cleaning up ----------

/** The clean-up a straightened page gets. [label] is its chip. */
enum class PageCleanup(val label: String) { None("Colour"), Clean("Clean"), Mono("Black and white") }

/** Cells across and down that the paper's brightness is measured in: shadows on a page change slowly. */
private const val CELLS = 16

// Clean-up stretches brightness so ink at or below INK turns black and paper at or above PAPER (once evened out) white
private const val INK = 40f
private const val PAPER = 230f

private fun lumOf(argb: Int) = ((argb shr 16 and 0xFF) * 299 + (argb shr 8 and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

/** The brightness [share] of [histogram]'s values are at or below. */
private fun percentile(histogram: IntArray, total: Int, share: Float): Int {
    var seen = 0
    for (v in 0..255) {
        seen += histogram[v]
        if (seen >= total * share) return v
    }
    return 255
}

/**
 * How bright the paper is over each part of a page ([pixels], ARGB, [width] x [height]), [CELLS] x [CELLS] cells row by row:
 * each cell's 90th-percentile brightness, so print doesn't count, never under 60% of the whole page's so a dark picture
 * on the page isn't taken for a shadow, then blended with its neighbours.
 */
fun paperLevels(pixels: IntArray, width: Int, height: Int): FloatArray {
    val whole = IntArray(256)
    val cells = Array(CELLS * CELLS) { IntArray(256) }
    for (y in 0 until height) for (x in 0 until width) {
        val v = lumOf(pixels[y * width + x])
        whole[v]++
        cells[(y * CELLS / height) * CELLS + x * CELLS / width][v]++
    }
    val floor = max(percentile(whole, pixels.size, 0.9f) * 0.6f, 1f)
    val raw = FloatArray(CELLS * CELLS) { i -> max(percentile(cells[i], cells[i].sum(), 0.9f).toFloat(), floor) }
    return FloatArray(CELLS * CELLS) { i ->
        val cx = i % CELLS
        val cy = i / CELLS
        var sum = 0f
        for (dy in -1..1) for (dx in -1..1) sum += raw[(cy + dy).coerceIn(0, CELLS - 1) * CELLS + (cx + dx).coerceIn(0, CELLS - 1)]
        sum / 9
    }
}

/**
 * Cleans up rows [top] onwards of a page [width] wide and [height] tall, in place: [pixels] holds as many whole rows as
 * fit. Each pixel is lifted by the paper's brightness around it ([levels], from [paperLevels]), so shadows even out and
 * paper turns white, then ink is darkened; colour stays unless [mono], which leaves black print on white with soft edges.
 */
fun clean(pixels: IntArray, width: Int, top: Int, height: Int, levels: FloatArray, mono: Boolean) {
    val row = FloatArray(CELLS)
    for (r in 0 until pixels.size / width) {
        // The levels at this row's height, one per column of cells, then blended across for each pixel
        val gy = ((top + r + 0.5f) * CELLS / height - 0.5f).coerceIn(0f, CELLS - 1f)
        val y0 = gy.toInt().coerceAtMost(CELLS - 2)
        val fy = gy - y0
        for (cx in 0 until CELLS) row[cx] = levels[y0 * CELLS + cx] * (1 - fy) + levels[(y0 + 1) * CELLS + cx] * fy
        for (x in 0 until width) {
            val gx = ((x + 0.5f) * CELLS / width - 0.5f).coerceIn(0f, CELLS - 1f)
            val x0 = gx.toInt().coerceAtMost(CELLS - 2)
            val fx = gx - x0
            val lift = 255f / (row[x0] * (1 - fx) + row[x0 + 1] * fx)
            val i = r * width + x
            val argb = pixels[i]
            pixels[i] = if (mono) {
                val v = ((lumOf(argb) * lift - 150f) * 255f / 60f).coerceIn(0f, 255f).toInt()
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                (0xFF shl 24) or (stretch(argb shr 16 and 0xFF, lift) shl 16) or (stretch(argb shr 8 and 0xFF, lift) shl 8) or stretch(argb and 0xFF, lift)
            }
        }
    }
}

/** One colour channel lifted by [lift], then stretched so ink goes black and paper white. */
private fun stretch(channel: Int, lift: Float) = ((channel * lift - INK) * 255f / (PAPER - INK)).coerceIn(0f, 255f).toInt()

// ---------- Straightening ----------

/**
 * [photo]'s page inside [corners] (fractions of the photo, in order from the top left) stretched flat to [size] with
 * Android's perspective matrix, then cleaned up as [cleanup] says.
 */
private fun straighten(photo: Bitmap, corners: List<Offset>, size: IntSize, cleanup: PageCleanup): Bitmap {
    val from = corners.flatMap { listOf(it.x * photo.width, it.y * photo.height) }.toFloatArray()
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val matrix = Matrix().apply { setPolyToPoly(from, 0, floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, 4) }
    val page = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    AndroidCanvas(page).drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    if (cleanup != PageCleanup.None) cleanUp(page, mono = cleanup == PageCleanup.Mono)
    return page
}

/** Runs [clean] over [page] a band of rows at a time, so a big page never needs a second copy of itself in memory. */
private fun cleanUp(page: Bitmap, mono: Boolean) {
    // The paper is measured on a small copy: shadows change slowly across a page
    val scale = min(1f, GUESS_PX * 2f / max(page.width, page.height))
    val small = Bitmap.createScaledBitmap(page, (page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), true)
    val smallPixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
    val levels = paperLevels(smallPixels, small.width, small.height)
    if (small !== page) small.recycle()
    val band = 256
    val pixels = IntArray(page.width * band)
    for (top in 0 until page.height step band) {
        val rows = min(band, page.height - top)
        val slice = if (rows == band) pixels else IntArray(page.width * rows)
        page.getPixels(slice, 0, page.width, 0, top, page.width, rows)
        clean(slice, page.width, top, page.height, levels, mono)
        page.setPixels(slice, 0, page.width, 0, top, page.width, rows)
    }
}

/** The photo's brightness at most [GUESS_PX] on its longest side, for [guessPage]. */
private fun guessFrom(photo: Bitmap): List<Offset>? {
    val scale = min(1f, GUESS_PX.toFloat() / max(photo.width, photo.height))
    val small = Bitmap.createScaledBitmap(photo, (photo.width * scale).roundToInt().coerceAtLeast(1), (photo.height * scale).roundToInt().coerceAtLeast(1), true)
    val pixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
    if (small !== photo) small.recycle()
    return guessPage(IntArray(pixels.size) { lumOf(pixels[it]) }, small.width, small.height)
}

// ---------- Offering it ----------

/**
 * Whether Straighten document is worth offering for [m]: a photo the index files under Documents or found words in, or
 * one it hasn't looked at yet. Never a video or a screenshot, which is flat already.
 */
suspend fun mayBePage(m: Media): Boolean {
    if (m.isVideo || isScreenshot(m.path, m.name)) return false
    return withContext(Dispatchers.IO) {
        runCatching {
            SmartIndex.placeOf(m.id) == null || m.id in SmartIndex.mediaIdsFor(Category.Documents).first() || looksLikeText(SmartIndex.textOf(m.id))
        }.getOrDefault(true) // no index to ask: offer it
    }
}

// ---------- Saving ----------

/** Room left over after saving, as for rotated copies, so a copy never fills the phone up. */
private const val SPARE = 50L shl 20

private val SCAN_SUFFIX = Regex("""_scan(?: \(\d+\))?$""")

/** The name for a straightened copy of [name]: "IMG_20241024_140512.jpg" becomes "IMG_20241024_140512_scan.jpg", never "_scan_scan". */
fun scanName(name: String): String = "${name.substringBeforeLast('.').replace(SCAN_SUFFIX, "").ifEmpty { "Photo" }}_scan.jpg"

/** How [saveStraightCopy] went: the new photo and the album it's in, or words for why not. */
sealed interface StraightCopy {
    class Saved(val uri: Uri, val album: String) : StraightCopy

    class Failed(val message: String) : StraightCopy
}

/**
 * Saves the page inside [corners] of [m] (fractions of the upright photo), straightened and cleaned up as [cleanup] says,
 * as a new JPEG next to it (or in Scans where Album saves, if it can't add photos there), with its date, camera details,
 * place and favourite. The original is only ever read. As sharp as the phone's free memory allows (see [pixelBudget]).
 * Finishes even if the viewer closes meanwhile.
 */
suspend fun saveStraightCopy(context: Context, m: Media, corners: List<Offset>, cleanup: PageCleanup): StraightCopy = withContext(NonCancellable + Dispatchers.IO) {
    val resolver = context.contentResolver
    // Without this Android blanks the GPS tags it reads out, and the copy would lose where it was taken
    val source = if (context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) MediaStore.setRequireOriginal(m.uri) else m.uri
    val to = if (canHold(m.path, isVideo = false)) SaveLocation(volumeOf(resolver, m.uri), m.path) else SavePrefs.target(context).inside("Scans")
    val album = to.folder.trimEnd('/').substringAfterLast('/')
    val taken = Gallery.media.value.orEmpty().filter { folderKey(it.path) == folderKey(to.folder) }.map { it.name }
    val name = freeNames(listOf(scanName(m.name)), taken).single()
    val scratch = File(context.cacheDir, "scan-${m.id}.tmp")
    try {
        if (context.filesDir.usableSpace < 2 * maxOf(m.size, m.width.toLong() * m.height / 2) + SPARE) {
            return@withContext StraightCopy.Failed("Not enough space to save a copy")
        }
        // The photo and its page in memory at once, as a re-saved rotated copy needs its photo and the turned copy
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val budget = pixelBudget(memory.availMem)
        val pixels = m.width.toLong() * m.height
        val longest = if (pixels <= 0) 8192 else (max(m.width, m.height) * min(1.0, sqrt(budget.toDouble() / pixels))).toInt()
        val photo = checkNotNull(decodeUpright(context, m.uri, longest)) { "can't decode the photo" }
        val page = try {
            val size = fitPixels(pageSize(corners.map { Offset(it.x * photo.width, it.y * photo.height) }), photo.width.toLong() * photo.height)
            straighten(photo, corners, size, cleanup)
        } finally {
            photo.recycle()
        }
        try {
            scratch.outputStream().use { check(page.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "can't encode the page" } }
        } finally {
            page.recycle()
        }
        runCatching { resolver.openFileDescriptor(source, "r")!!.use { ExifInterface(it.fileDescriptor) } }.getOrNull()?.let { carryExif(it, scratch) }
        val uri = publish(resolver, m, name, to, "image/jpeg") { copy -> pour(scratch.inputStream(), resolver, copy) }
        StraightCopy.Saved(uri, album)
    } catch (e: Exception) {
        CrashLog.note("straight copy failed: $e")
        StraightCopy.Failed("Couldn't save a straightened copy")
    } catch (e: OutOfMemoryError) {
        CrashLog.note("straight copy out of memory: ${m.width} x ${m.height}")
        StraightCopy.Failed("Not enough memory to save a straightened copy")
    } finally {
        scratch.delete()
    }
}

// ---------- The screen ----------

/** Longest side the photo is shown at while its corners are placed: sharp on the screen, quick to straighten for the preview. */
private const val SCAN_PX = 2048

/** Longest side of the straightened preview: big enough to read print when it's opened full size. */
private const val PREVIEW_PX = 1280

/** Straighten document over photo [m]: where its corners are and how it's cleaned up. Nothing is written until Save. */
@Stable
class DocumentScan(val m: Media) {
    /** The photo upright, at most [SCAN_PX]; null while it loads. */
    var photo by mutableStateOf<Bitmap?>(null)
        internal set

    /** The page's corners in fractions of the photo, top left, top right, bottom right, bottom left; empty while loading. */
    val corners = mutableStateListOf<Offset>()

    var cleanup by mutableStateOf(PageCleanup.None)

    /** While saving, everything waits. */
    var saving by mutableStateOf(false)

    /** The straightened page, made again whenever a corner is let go or the clean-up changes. */
    var preview by mutableStateOf<ImageBitmap?>(null)
        internal set
}

/**
 * The Straighten document screen, over the viewer: the photo with the page outlined and a handle on each corner (a loupe
 * shows what's under the finger while dragging), then a panel with the straightened page small (tap it to see it full
 * size), the clean-up chips, Discard and Save. Save waits until the corners make a page shape. [onPanel] hears the
 * panel's height, to keep the viewer's snackbar above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentScreen(scan: DocumentScan, onDiscard: () -> Unit, onSave: () -> Unit, onPanel: (heightPx: Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    var dragging by remember(scan) { mutableStateOf<Int?>(null) }
    var full by remember(scan) { mutableStateOf(false) } // the straightened page, full size
    BackHandler(enabled = full) { full = false }
    LaunchedEffect(scan) {
        val photo = withContext(Dispatchers.IO) { decodeUpright(context, scan.m.uri, SCAN_PX) }
        if (photo == null) {
            CrashLog.note("straighten: can't decode")
            return@LaunchedEffect onDiscard()
        }
        val guess = withContext(Dispatchers.Default) { guessFrom(photo) }
        CrashLog.note("page ${if (guess == null) "not found" else "found"}")
        scan.corners.addAll(guess ?: INSET)
        scan.photo = photo
    }
    // Only between drags: the preview isn't remade while a corner moves
    val settled by remember(scan) { derivedStateOf { scan.corners.toList().takeIf { dragging == null && it.size == 4 } } }
    val shaped = settled?.let(::isPageShape) ?: true
    LaunchedEffect(scan.photo, settled, scan.cleanup) {
        val photo = scan.photo ?: return@LaunchedEffect
        val corners = settled?.takeIf(::isPageShape) ?: return@LaunchedEffect
        scan.preview = withContext(Dispatchers.Default) {
            val size = pageSize(corners.map { Offset(it.x * photo.width, it.y * photo.height) })
            val scale = min(1f, PREVIEW_PX.toFloat() / max(size.width, size.height))
            straighten(photo, corners, IntSize((size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1)), scan.cleanup).asImageBitmap()
        }
    }

    Column(modifier.fillMaxSize().background(c.void).pointerInput(Unit) {}) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.s4, vertical = Space.s3)) {
            Text("Straighten document", style = type.label, color = c.onPhoto)
            Text(
                when {
                    full -> "Tap the page to go back to the corners"
                    !shaped -> "The corners cross. Drag them back to the page's edges."
                    else -> "Drag the corners to the page's edges"
                },
                style = type.caption,
                color = c.onPhoto,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val preview = scan.preview
            if (full && preview != null) {
                Image(
                    preview,
                    "Straightened page",
                    Modifier.fillMaxSize().padding(Space.s4).clickable(onClickLabel = "Back to the corners") { full = false },
                    contentScale = ContentScale.Fit,
                )
            } else {
                CornerEditor(scan, dragging) { dragging = it }
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .onSizeChanged { onPanel(it.height) }
                .background(c.surface, RoundedCornerShape(topStart = Space.radiusLg, topEnd = Space.radiusLg))
                .navigationBarsPadding()
                .padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                val preview = scan.preview
                Box(
                    Modifier.size(64.dp, 88.dp).clip(RoundedCornerShape(Space.radiusMd)).background(c.surfaceHigh)
                        .clickable(enabled = preview != null, role = Role.Button, onClickLabel = if (full) "Back to the corners" else "See the straightened page") {
                            CrashLog.note("straighten preview ${!full}")
                            full = !full
                        },
                    contentAlignment = Alignment.Center,
                ) { preview?.let { Image(it, "Straightened page", Modifier.fillMaxSize().padding(Space.s1), contentScale = ContentScale.Fit) } }
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    PageCleanup.entries.forEach { option ->
                        Chip(option.label, {
                            CrashLog.note("straighten ${option.name}")
                            scan.cleanup = option
                        }, selected = scan.cleanup == option)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                AlbumButton("Discard", onDiscard, Modifier.weight(1f).heightIn(min = 56.dp), enabled = !scan.saving)
                AlbumButton(
                    if (scan.saving) "Saving" else "Save",
                    onSave,
                    Modifier.weight(1.5f).heightIn(min = 56.dp),
                    ButtonVariant.Primary,
                    enabled = !scan.saving && scan.photo != null && shaped,
                )
            }
        }
    }
}

/**
 * The photo, fitted, with the page outlined in flash and everything outside it dimmed. Each corner's handle follows the
 * finger from wherever it was grabbed, so it never jumps under the finger; [onDragging] says which one is moving.
 */
@Composable
private fun CornerEditor(scan: DocumentScan, dragging: Int?, onDragging: (Int?) -> Unit) {
    val photo = scan.photo ?: return
    val c = AlbumTheme.colors
    val haptics = rememberHaptics()
    val image = remember(photo) { photo.asImageBitmap() }
    var finger by remember { mutableStateOf(Offset.Zero) }
    // Room round the photo, so a corner on its edge can still be grabbed and seen
    BoxWithConstraints(Modifier.fillMaxSize().padding(Space.s6)) {
        val area = with(LocalDensity.current) { fitRect(photo.width, photo.height, Size(maxWidth.toPx(), maxHeight.toPx())) }
        val onScreen = { corner: Offset -> Offset(area.left + corner.x * area.width, area.top + corner.y * area.height) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(scan, area) {
                val reach = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (scan.saving || scan.corners.size != 4) return@awaitEachGesture
                    val handles = scan.corners.map(onScreen)
                    val i = handles.indices.minBy { (handles[it] - down.position).getDistance() }
                    if ((handles[i] - down.position).getDistance() > reach) return@awaitEachGesture
                    down.consume()
                    val grab = handles[i] - down.position
                    finger = down.position
                    onDragging(i)
                    haptics.lightTick()
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            finger = change.position
                            val p = change.position + grab
                            scan.corners[i] = Offset(((p.x - area.left) / area.width).coerceIn(0f, 1f), ((p.y - area.top) / area.height).coerceIn(0f, 1f))
                        }
                    } finally {
                        onDragging(null) // a cancelled drag too, or the preview would never be made again
                    }
                }
            },
        ) {
            val dst = IntOffset(area.left.roundToInt(), area.top.roundToInt())
            val dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt())
            drawImage(image, dstOffset = dst, dstSize = dstSize)
            if (scan.corners.size != 4) return@Canvas
            val handles = scan.corners.map(onScreen)
            val outline = Path().apply {
                moveTo(handles[0].x, handles[0].y)
                handles.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            clipPath(outline, ClipOp.Difference) { drawRect(Color.Black, area.topLeft, area.size, alpha = 0.5f) }
            drawPath(outline, c.flash, style = Stroke(2.dp.toPx()))
            handles.forEachIndexed { i, p ->
                val r = (if (i == dragging) 14 else 11).dp.toPx()
                drawCircle(c.onFlash, r + 2.dp.toPx(), p)
                drawCircle(c.flash, r, p)
            }
            dragging?.let { loupe(image, dst, dstSize, outline, handles[it], finger, c.flash, c.onPhoto, c.void) }
        }
    }
}

/**
 * A magnifier at the top of the editor, on the side away from the finger: the photo around [corner] three times as big,
 * with the outline and the corner itself marked, so it can be placed exactly though the finger covers it.
 */
private fun DrawScope.loupe(image: ImageBitmap, dst: IntOffset, dstSize: IntSize, outline: Path, corner: Offset, finger: Offset, mark: Color, ring: Color, void: Color) {
    val r = 56.dp.toPx()
    val zoom = 3f
    val gap = Space.s2.toPx()
    val centre = Offset(if (finger.x < size.width / 2) size.width - r - gap else r + gap, r + gap)
    clipPath(Path().apply { addOval(Rect(centre, r)) }) {
        drawRect(void, centre - Offset(r, r), Size(2 * r, 2 * r))
        withTransform({
            translate(centre.x - corner.x * zoom, centre.y - corner.y * zoom)
            scale(zoom, zoom, Offset.Zero)
        }) {
            drawImage(image, dstOffset = dst, dstSize = dstSize)
            drawPath(outline, mark, style = Stroke(2.dp.toPx() / zoom))
        }
    }
    drawCircle(ring, r, centre, style = Stroke(2.dp.toPx()))
    drawCircle(mark, 3.dp.toPx(), centre)
}
