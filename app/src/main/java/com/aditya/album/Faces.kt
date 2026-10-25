package com.aditya.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bump when detection, alignment or quality rules change, so every photo is scanned again. People's names and
 * corrections stay. A new embedding model also needs FaceDb's version bumped, so no old embedding is compared with a new one.
 */
const val FACE_INDEX_VERSION = 3 // 3: YuNet and EdgeFace-S, faces cut from the full-size photo; 2: three-quarter views kept

/** Floats in a face embedding (EdgeFace-S). */
const val EMBEDDING = 512

// YuNet (OpenCV Zoo, MIT) finds faces and five landmarks; EdgeFace-S (Idiap, CC BY-NC-SA 4.0) turns each face into an
// embedding. Both run on LiteRT, on the CPU, one thread. See licenses/, tools/yunet_to_tflite.py, tools/edgeface_to_tflite.py.
private const val DETECTOR_ASSET = "faces/yunet.tflite"
private const val EMBEDDER_ASSET = "faces/edgeface_s.tflite"
private const val DETECT = 640 // photos are decoded to fit DETECT x DETECT to find faces; YuNet finds faces of 10 to 300 px
private const val VIEW = 160 // each face is cut into a VIEW x VIEW picture with the template's 112 x 112 in the middle
private const val INPUT = 112 // EdgeFace's input
private const val PAD = (VIEW - INPUT) / 2
private const val MIN_SCORE = 0.6f // YuNet's own default
private const val VERIFY_SCORE = 0.5f
private const val OVERLAP = 0.3f // boxes overlapping more than this are one face
private const val MAX_FACES = 30 // per photo, biggest first: the rest of a crowd are strangers
private val STRIDES = intArrayOf(8, 16, 32)

/**
 * Eyes closer than this, in full-size pixels, carry too little detail: on the evaluation photos (FaceEvalTest) such
 * faces matched strangers at up to 0.41, against 0.36 for every bigger face.
 */
private const val MIN_EYES = 16f

/**
 * A cut face is checked by finding it again in the cut (YuNet on the 160 px view): its landmarks must land within this
 * many template pixels (RMS) of the template. Garbage landmarks (a sideways head, a machine part taken for a face) give
 * cuts that fail, and such cuts all look alike to EdgeFace: on the evaluation photos they matched each other at 0.8.
 * Real faces landed 4.2 px away (median); 90% within 11.
 */
private const val MAX_VERIFY_ERROR = 8f

// A weak face may join a person it clearly matches, but never starts a person or a merge. On the evaluation photos
// these rules made 9 of 80 labelled faces weak and kept every strong pair of different people under 0.39. Every
// labelled face that passed the check scored 0.9 or more with YuNet, while faces scoring 0.8 to 0.9 (all strangers)
// matched other people at up to 0.43, the most of any band. Calibration knobs.
private const val WEAK_EYES = 24f // full-size pixels between the eyes
private const val WEAK_VERIFY_ERROR = 6f
private const val WEAK_TURN = 0.5f // headTurn(): about 45 degrees
private const val WEAK_SHARPNESS = 25f // sharpness() of the 112 px cut; sharp faces score in the hundreds (labelled ones 185 or more)
private const val WEAK_SCORE = 0.9f

// ArcFace's 112 x 112 landmark template (eyes, nose tip, mouth corners; image-left first), pixel centres on integers
// as OpenCV counts them. YuNet's five points come in the same order.
private val TEMPLATE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

/** The template where the view has it, pixel centres at +0.5 like every other position here. */
private val TEMPLATE_IN_VIEW = FloatArray(10) { TEMPLATE[it] + PAD + 0.5f }

/**
 * A face worth recognising: where it is, its five landmarks in the upright full-size photo's pixels (for the evaluation),
 * how good a picture of the person it is (0 to 1, see faceQuality), whether it's [weak], and its unit-length embedding.
 */
class FoundFace(val box: FaceBox, val landmarks: FloatArray, val quality: Float, val weak: Boolean, val embedding: FloatArray)

/** A face YuNet found: box and five landmarks (x, y pairs; eyes, nose tip, mouth corners, image-left first) and score. */
class Detection(val left: Float, val top: Float, val right: Float, val bottom: Float, val points: FloatArray, val score: Float)

/**
 * Finds faces in a photo and turns each into an embedding: YuNet on the photo decoded at 640 px, then per face a cut from
 * the full-size photo onto the ArcFace template (5-point similarity), checked by finding the face again in the cut, then
 * EdgeFace-S. Runs on the caller's thread: give it the indexer's background-priority thread. Not thread-safe.
 */
class FaceAnalyser(private val context: Context) : Closeable {
    private val interpreters = ArrayList<Interpreter>(3)
    private val detector: YuNet
    private val verifier: YuNet
    private val embedder: Interpreter

    init {
        try {
            val yunet = loadModel(DETECTOR_ASSET)
            detector = YuNet(open(yunet), DETECT)
            verifier = YuNet(open(yunet), VIEW)
            embedder = open(loadModel(EMBEDDER_ASSET))
        } catch (e: Throwable) {
            interpreters.forEach { it.close() } // close() is never reached when the constructor throws
            throw e
        }
    }

    private val embedderIn = floats(INPUT * INPUT * 3)
    private val embedding = Array(1) { FloatArray(EMBEDDING) }
    private val view = Bitmap.createBitmap(VIEW, VIEW, Bitmap.Config.ARGB_8888)
    private val viewPixels = IntArray(VIEW * VIEW)
    private val cut = IntArray(INPUT * INPUT)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Faces found but not kept, by reason, for the timing breadcrumb: where the gates above need calibrating. */
    val dropped = sortedMapOf<String, Int>()

    /** The faces worth recognising in the photo at [uri] (maybe none), or null when it can't be read. */
    fun analyse(uri: Uri): List<FoundFace>? {
        val (photo, width, height) = decode(uri) ?: return null
        try {
            val found = detect(photo)
            if (found.isEmpty()) return emptyList()
            if (found.size > MAX_FACES) dropped.merge("crowd", found.size - MAX_FACES, Int::plus)
            val biggest = found.sortedByDescending { (it.right - it.left) * (it.bottom - it.top) }.take(MAX_FACES)
            return FullSize.open(context, uri, width, height).use { full ->
                biggest.mapNotNull { face(it, photo, width, height, full, alone = found.size == 1) }
            }
        } finally {
            photo.recycle()
        }
    }

    /** The photo upright, fitting DETECT x DETECT, and the upright full size. Null when it can't be read. */
    private fun decode(uri: Uri): Triple<Bitmap, Int, Int>? = try {
        var size = Size(0, 0)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            size = info.size
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val scale = DETECT.toFloat() / max(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        // 10-bit HDR photos decode to other configs
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        Triple(argb, size.width, size.height)
    } catch (e: Exception) {
        null // corrupt, vanished or unsupported: saved with no faces so it isn't retried every pass
    }

    /** YuNet on [photo], placed top-left in its DETECT x DETECT input (the rest black, as OpenCV pads). */
    private fun detect(photo: Bitmap): List<Detection> {
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        return detector.faces(MIN_SCORE) { x, y -> if (x < photo.width && y < photo.height) pixels[y * photo.width + x] else Color.BLACK }
    }

    private fun face(d: Detection, photo: Bitmap, width: Int, height: Int, full: FullSize?, alone: Boolean): FoundFace? {
        val sx = width.toFloat() / photo.width
        val sy = height.toFloat() / photo.height
        val points = FloatArray(10) { d.points[it] * if (it % 2 == 0) sx else sy } // to full-size pixels
        val eyes = hypot(points[2] - points[0], points[3] - points[1])
        if (eyes < MIN_EYES) return drop("small")
        val t = similarity(points, TEMPLATE_IN_VIEW)
        val toView = floatArrayOf(t[0], -t[1], t[2], t[1], t[0], t[3]) // full-size upright pixels to the view's
        // Cut from the full-size photo; if no face shows in that cut (a photo whose stored orientation fooled us), from
        // the photo already decoded, which is upright for sure. Only then: it's softer, so a face that merely fails the
        // check on the sharp cut doesn't get a second, blurrier chance
        var check = if (full != null && full.cut(toView, view, paint)) verify() else null
        if (check == null) {
            cutFrom(photo, affineThen(floatArrayOf(sx, 0f, 0f, 0f, sy, 0f), toView))
            check = verify()
        }
        if (check == null || check.second > MAX_VERIFY_ERROR) return drop("unlike a face")

        view.getPixels(viewPixels, 0, VIEW, 0, 0, VIEW, VIEW)
        for (y in 0 until INPUT) System.arraycopy(viewPixels, (y + PAD) * VIEW + PAD, cut, y * INPUT, INPUT)
        val sharpness = sharpness(cut, INPUT, INPUT)
        val turn = headTurn(points)
        val roll = Math.toDegrees(atan2(t[1], t[0]).toDouble()).toFloat()
        val weak = eyes < WEAK_EYES || abs(turn) > WEAK_TURN || check.second > WEAK_VERIFY_ERROR || sharpness < WEAK_SHARPNESS || d.score < WEAK_SCORE

        embedderIn.rewind()
        for (p in cut) { // RGB scaled to -1..1: EdgeFace's (x / 255 - 0.5) / 0.5
            embedderIn.putFloat(((p shr 16 and 0xff) - 127.5f) / 127.5f)
            embedderIn.putFloat(((p shr 8 and 0xff) - 127.5f) / 127.5f)
            embedderIn.putFloat(((p and 0xff) - 127.5f) / 127.5f)
        }
        embedderIn.rewind()
        embedder.run(embedderIn, embedding)
        val box = FaceBox(
            (d.left / photo.width).coerceIn(0f, 1f),
            (d.top / photo.height).coerceIn(0f, 1f),
            (d.right / photo.width).coerceIn(0f, 1f),
            (d.bottom / photo.height).coerceIn(0f, 1f),
        )
        val quality = faceQuality(eyes, sharpness, turn, roll, brightness(cut), d.score, alone)
        return FoundFace(box, points, quality, weak, unit(embedding[0]))
    }

    private fun cutFrom(source: Bitmap, toView: FloatArray) = Canvas(view).run {
        drawColor(Color.BLACK) // outside the photo stays black, as in OpenCV's warpAffine
        drawBitmap(source, matrixOf(toView), paint)
    }

    /** YuNet on the cut: the score and landmark error (template pixels, RMS) of the face nearest its middle, or null. */
    private fun verify(): Pair<Float, Float>? {
        view.getPixels(viewPixels, 0, VIEW, 0, 0, VIEW, VIEW)
        val face = verifier.faces(VERIFY_SCORE) { x, y -> viewPixels[y * VIEW + x] }
            .minByOrNull { hypot((it.left + it.right) / 2 - VIEW / 2f, (it.top + it.bottom) / 2 - VIEW / 2f) } ?: return null
        var squares = 0f
        for (i in 0 until 10) squares += (face.points[i] - TEMPLATE_IN_VIEW[i]).let { it * it }
        return face.score to sqrt(squares / 5)
    }

    private fun drop(reason: String): FoundFace? {
        dropped.merge(reason, 1, Int::plus)
        return null
    }

    // Memory-mapped from the APK: the assets are stored uncompressed (AGP never compresses .tflite).
    private fun loadModel(asset: String): MappedByteBuffer = context.assets.openFd(asset).use { fd ->
        FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }

    /** An interpreter on one thread: no extra threads at normal priority. */
    private fun open(model: MappedByteBuffer) = Interpreter(model, Interpreter.Options().setNumThreads(1)).also { interpreters += it }

    override fun close() {
        interpreters.forEach { it.close() }
        view.recycle()
    }
}

/** YuNet sized for [side] x [side] pictures, its outputs found by name so they're passed to yunetFaces in its order. */
private class YuNet(private val model: Interpreter, private val side: Int) {
    private val input = floats(side * side * 3)
    private val outputs = listOf("cls", "obj", "bbox", "kps").flatMap { kind -> STRIDES.map { kind to it } }.map { (kind, stride) ->
        model.getOutputIndex("${kind}_$stride") to floats((side / stride) * (side / stride) * when (kind) { "bbox" -> 4; "kps" -> 10; else -> 1 })
    }

    init {
        model.resizeInput(0, intArrayOf(1, side, side, 3))
        model.allocateTensors()
    }

    /** Faces scoring [minScore] or more in the picture whose ARGB pixels [pixel] gives (input is BGR, 0 to 255). */
    fun faces(minScore: Float, pixel: (x: Int, y: Int) -> Int): List<Detection> {
        input.rewind()
        for (y in 0 until side) for (x in 0 until side) {
            val p = pixel(x, y)
            input.putFloat((p and 0xff).toFloat())
            input.putFloat((p shr 8 and 0xff).toFloat())
            input.putFloat((p shr 16 and 0xff).toFloat())
        }
        input.rewind()
        outputs.forEach { it.second.rewind() }
        model.runForMultipleInputsOutputs(arrayOf(input), outputs.associate { it })
        return yunetFaces(outputs.map { (_, b) -> FloatArray(b.capacity() / 4).also { b.rewind(); b.asFloatBuffer().get(it) } }, side, side, minScore)
    }
}

/**
 * The full-size photo, read a piece at a time. BitmapRegionDecoder reads stored pixels, before the EXIF orientation that
 * ImageDecoder applies, so pieces are mapped through [toUpright] (stored pixels to upright ones).
 */
private class FullSize(private val decoder: BitmapRegionDecoder, private val toUpright: FloatArray) : Closeable {
    /**
     * Draws into [view] the part of the photo that [toView] (upright full-size pixels to the view's) maps there, decoded
     * at the coarsest power-of-two sampling that still has a pixel per template pixel. False when nothing could be read.
     */
    fun cut(toView: FloatArray, view: Bitmap, paint: Paint): Boolean {
        val fromView = affineInverse(affineThen(toUpright, toView))
        val corners = listOf(0f to 0f, VIEW.toFloat() to 0f, 0f to VIEW.toFloat(), VIEW.toFloat() to VIEW.toFloat()).map { (x, y) -> affineMap(fromView, x, y) }
        val sample = Integer.highestOneBit(max(1, (1 / hypot(toView[0], toView[3])).toInt())) // full-size pixels per view pixel, rounded down
        // A pixel more all round for the bilinear filter, and edges on multiples of the sampling: decoders sample on that
        // grid, so the piece's pixels then sit exactly where toStored below says
        fun down(v: Float) = floor((v - 1) / sample).toInt() * sample
        fun up(v: Float) = ceil((v + 1) / sample).toInt() * sample
        val rect = Rect(
            down(corners.minOf { it.first }).coerceAtLeast(0),
            down(corners.minOf { it.second }).coerceAtLeast(0),
            up(corners.maxOf { it.first }).coerceAtMost(decoder.width),
            up(corners.maxOf { it.second }).coerceAtMost(decoder.height),
        )
        if (rect.isEmpty) return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val piece = try {
            decoder.decodeRegion(rect, options)
        } catch (e: Exception) {
            null
        } ?: return false
        // The piece's pixels to stored ones (the sampling actually used), then upright, then the view's
        val toStored = floatArrayOf(rect.width().toFloat() / piece.width, 0f, rect.left.toFloat(), 0f, rect.height().toFloat() / piece.height, rect.top.toFloat())
        Canvas(view).run {
            drawColor(Color.BLACK)
            drawBitmap(piece, matrixOf(affineThen(affineThen(toStored, toUpright), toView)), paint)
        }
        piece.recycle()
        return true
    }

    override fun close() = decoder.recycle()

    companion object {
        /**
         * The photo at [uri] for cutting faces from, or null when it can't be read a piece at a time (then faces are cut
         * from the decoded photo). Its stored size must be the upright [width] x [height], turned as EXIF says.
         */
        fun open(context: Context, uri: Uri, width: Int, height: Int): FullSize? = try {
            val orientation = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            context.contentResolver.openFileDescriptor(uri, "r")?.use { BitmapRegionDecoder.newInstance(it) }?.let { decoder ->
                val turned = orientation in 5..8
                // Stored and upright sizes that disagree with EXIF (a HEIF's own rotation, say): don't trust the mapping
                if ((if (turned) decoder.height else decoder.width) != width || (if (turned) decoder.width else decoder.height) != height) {
                    decoder.recycle()
                    null
                } else {
                    FullSize(decoder, uprightFromStored(orientation, decoder.width, decoder.height))
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

private fun floats(n: Int): ByteBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())

private fun matrixOf(m: FloatArray) = Matrix().apply { setValues(floatArrayOf(m[0], m[1], m[2], m[3], m[4], m[5], 0f, 0f, 1f)) }

/**
 * YuNet's raw outputs for a [width] x [height] input, in the order cls, obj, bbox, kps, each at strides 8, 16 and 32.
 * Returns the faces scoring [minScore] or more, overlapping ones merged (best first), with positions in input pixels and
 * pixel centres at +0.5. The decoding is OpenCV's FaceDetectorYN::postProcess.
 */
fun yunetFaces(outputs: List<FloatArray>, width: Int, height: Int, minScore: Float): List<Detection> {
    val found = ArrayList<Detection>()
    for ((i, stride) in STRIDES.withIndex()) {
        val cols = width / stride
        val cls = outputs[i]
        val obj = outputs[3 + i]
        val bbox = outputs[6 + i]
        val kps = outputs[9 + i]
        for (index in cls.indices) {
            val score = sqrt(cls[index].coerceIn(0f, 1f) * obj[index].coerceIn(0f, 1f))
            if (score < minScore) continue
            val c = index % cols
            val r = index / cols
            val cx = (c + bbox[4 * index]) * stride
            val cy = (r + bbox[4 * index + 1]) * stride
            val w = exp(bbox[4 * index + 2]) * stride
            val h = exp(bbox[4 * index + 3]) * stride
            val points = FloatArray(10) { (kps[10 * index + it] + if (it % 2 == 0) c else r) * stride + 0.5f }
            found += Detection(cx - w / 2 + 0.5f, cy - h / 2 + 0.5f, cx + w / 2 + 0.5f, cy + h / 2 + 0.5f, points, score)
        }
    }
    found.sortByDescending { it.score }
    val kept = ArrayList<Detection>()
    for (d in found) if (kept.none { overlap(it, d) > OVERLAP }) kept += d
    return kept
}

private fun overlap(a: Detection, b: Detection) = iou(FaceBox(a.left, a.top, a.right, a.bottom), FaceBox(b.left, b.top, b.right, b.bottom))

/**
 * Least-squares similarity transform (uniform scale, rotation, shift) taking points [src] onto [dst] (x, y pairs),
 * as [a, b, tx, ty] meaning x' = a x - b y + tx, y' = b x + a y + ty. Same fit as OpenCV's face alignment.
 */
fun similarity(src: FloatArray, dst: FloatArray): FloatArray {
    val n = src.size / 2
    val srcX = (0 until n).map { src[2 * it] }.average().toFloat()
    val srcY = (0 until n).map { src[2 * it + 1] }.average().toFloat()
    val dstX = (0 until n).map { dst[2 * it] }.average().toFloat()
    val dstY = (0 until n).map { dst[2 * it + 1] }.average().toFloat()
    var norm = 0f
    var a = 0f
    var b = 0f
    for (i in 0 until n) {
        val px = src[2 * i] - srcX
        val py = src[2 * i + 1] - srcY
        val qx = dst[2 * i] - dstX
        val qy = dst[2 * i + 1] - dstY
        norm += px * px + py * py
        a += px * qx + py * qy
        b += px * qy - py * qx
    }
    a /= norm
    b /= norm
    return floatArrayOf(a, b, dstX - (a * srcX - b * srcY), dstY - (b * srcX + a * srcY))
}

// Affine maps as [a, b, c, d, e, f]: x' = a x + b y + c, y' = d x + e y + f.

fun affineMap(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

/** [first], then [then]. */
fun affineThen(first: FloatArray, then: FloatArray) = floatArrayOf(
    then[0] * first[0] + then[1] * first[3], then[0] * first[1] + then[1] * first[4], then[0] * first[2] + then[1] * first[5] + then[2],
    then[3] * first[0] + then[4] * first[3], then[3] * first[1] + then[4] * first[4], then[3] * first[2] + then[4] * first[5] + then[5],
)

fun affineInverse(m: FloatArray): FloatArray {
    val det = m[0] * m[4] - m[1] * m[3]
    val a = m[4] / det
    val b = -m[1] / det
    val d = -m[3] / det
    val e = m[0] / det
    return floatArrayOf(a, b, -(a * m[2] + b * m[5]), d, e, -(d * m[2] + e * m[5]))
}

/**
 * EXIF [orientation] (1 to 8) as the map from a [width] x [height] stored picture's pixels to the upright picture's,
 * positions measured from pixel corners. Unknown values count as upright.
 */
fun uprightFromStored(orientation: Int, width: Int, height: Int): FloatArray {
    val w = width.toFloat()
    val h = height.toFloat()
    return when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f)
        ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h)
        ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
        ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
        ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, h, -1f, 0f, w)
        ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    }
}

/**
 * How far the head is turned, from five landmarks: the nose tip's distance from the face's middle line (eyes to mouth),
 * in eye spacings. About 0 facing the camera, 0.25 at roughly 30 degrees, 0.5 past 45. The sign says which way.
 */
fun headTurn(p: FloatArray): Float {
    val eyesX = (p[0] + p[2]) / 2
    val eyesY = (p[1] + p[3]) / 2
    val downX = (p[6] + p[8]) / 2 - eyesX
    val downY = (p[7] + p[9]) / 2 - eyesY
    val length = hypot(downX, downY)
    val spacing = hypot(p[2] - p[0], p[3] - p[1])
    if (length == 0f || spacing == 0f) return Float.MAX_VALUE
    // The nose tip from the middle of the face, across the eyes-to-mouth line
    val nx = p[4] - (eyesX + downX / 2)
    val ny = p[5] - (eyesY + downY / 2)
    return (nx * downY - ny * downX) / length / spacing
}

/** Variance of the Laplacian of an ARGB image's brightness: how much fine detail it has. Low for blurred or blown-up pictures. */
fun sharpness(argb: IntArray, width: Int, height: Int): Float {
    val grey = FloatArray(argb.size) { (0.299f * (argb[it] shr 16 and 0xff) + 0.587f * (argb[it] shr 8 and 0xff) + 0.114f * (argb[it] and 0xff)) }
    var sum = 0.0
    var sumSquares = 0.0
    for (y in 1 until height - 1) for (x in 1 until width - 1) {
        val i = y * width + x
        val l = (grey[i - 1] + grey[i + 1] + grey[i - width] + grey[i + width] - 4 * grey[i]).toDouble()
        sum += l
        sumSquares += l * l
    }
    val n = (width - 2) * (height - 2)
    return (sumSquares / n - (sum / n) * (sum / n)).toFloat()
}

/** Mean brightness of an ARGB image, 0 (black) to 1 (white). */
fun brightness(argb: IntArray): Float =
    argb.sumOf { (0.299 * (it shr 16 and 0xff) + 0.587 * (it shr 8 and 0xff) + 0.114 * (it and 0xff)) }.toFloat() / argb.size / 255

/**
 * 0 to 1: how good a picture of the person a face is, for choosing their cover. Bigger, sharper, facing the camera,
 * level, evenly lit, confidently found and alone in its photo all score higher. Calibration knobs, not tuned.
 */
fun faceQuality(eyes: Float, sharpness: Float, turn: Float, roll: Float, brightness: Float, score: Float, alone: Boolean): Float =
    min(1f, eyes / 48f) * min(1f, sharpness / 300f) * (1 - min(1f, abs(turn) / 0.6f)) * (1 - min(1f, abs(roll) / 90f)) *
        (1 - abs(brightness - 0.5f)) * score * (if (alone) 1f else 0.8f)

/** A square around [box] with room for hair and chin, inside a [width] x [height] picture, as [left, top, side] in pixels. */
fun avatarRect(box: FaceBox, width: Int, height: Int): IntArray {
    val side = (max((box.right - box.left) * width, (box.bottom - box.top) * height) * 1.6f).roundToInt().coerceIn(1, min(width, height))
    val left = (((box.left + box.right) / 2 * width) - side / 2f).roundToInt().coerceIn(0, width - side)
    val top = (((box.top + box.bottom) / 2 * height) - side / 2f).roundToInt().coerceIn(0, height - side)
    return intArrayOf(left, top, side)
}

fun FloatArray.toBytes(): ByteArray = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(this) }.array()

fun ByteArray.toFloats(): FloatArray = FloatArray(size / 4).also { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }

// --- Round face pictures for People, cut from the photo on demand (nothing extra is stored).

/** Coil model for a face picture in photo [mediaId]. Requests key it by [faceId] (see FaceImage), so each face is cut once per session. */
data class FaceCrop(val faceId: Long, val mediaId: Long, val box: FaceBox)

class FaceCropFetcher(private val crop: FaceCrop, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = options.size.width.pxOrElse { 256 }
        val resolver = options.context.contentResolver
        // Android's cached thumbnail is quickest; it's enough when the face is big in the photo. Otherwise decode just enough of it.
        val thumb = runCatching { resolver.loadThumbnail(uri, Size(512, 512), null) }.getOrNull()
        val bitmap = thumb?.let { cut(it, px / 2) } ?: decode(px)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun cut(thumb: Bitmap, minPx: Int): Bitmap? {
        val (left, top, side) = avatarRect(crop.box, thumb.width, thumb.height)
        val face = if (side >= minPx) Bitmap.createBitmap(thumb, left, top, side, side) else null
        if (face !== thumb) thumb.recycle()
        return face
    }

    private val uri get() = mediaUri(crop.mediaId, isVideo = false)

    private fun decode(px: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(options.context.contentResolver, uri)) { decoder, info, _ ->
        val (left, top, side) = avatarRect(crop.box, info.size.width, info.size.height)
        val scale = min(1f, px.toFloat() / side)
        val width = (info.size.width * scale).roundToInt().coerceAtLeast(1)
        val height = (info.size.height * scale).roundToInt().coerceAtLeast(1)
        decoder.setTargetSize(width, height)
        // The crop applies after scaling, so it's in scaled pixels; rounding must not push it past the edge
        val l = (left * scale).roundToInt().coerceAtMost(width - 1)
        val t = (top * scale).roundToInt().coerceAtMost(height - 1)
        val s = (side * scale).roundToInt().coerceIn(1, min(width - l, height - t))
        decoder.crop = Rect(l, t, l + s, t + s)
    }

    class Factory : Fetcher.Factory<FaceCrop> {
        override fun create(data: FaceCrop, options: Options, imageLoader: ImageLoader) = FaceCropFetcher(data, options)
    }
}
