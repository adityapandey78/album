package com.aditya.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bump when detection, alignment or quality rules change, so every photo is scanned again. People's names and corrections stay. */
const val FACE_INDEX_VERSION = 2 // 2: three-quarter views kept, unlike-a-face results dropped

/** The embedding model below. Saved person centroids from another model are ignored (they live in a different space). */
const val FACE_MODEL = 1

/** SFace (OpenCV Zoo, Apache 2.0), weights quantised to int8. See licenses/sface-Apache-2.0.txt and tools/sface_to_tflite.py. */
private const val MODEL_ASSET = "faces/sface.tflite"
private const val INPUT = 112 // SFace takes an aligned 112 x 112 RGB face (0 to 255; it normalises itself)
private const val EMBEDDING = 128
private const val DECODE_PX = 1024 // long edge photos are decoded at for detection
private const val MIN_FACE_PX = 40 // narrower faces (at DECODE_PX) carry too little detail to recognise
// Degrees. SFace still matches three-quarter views: on the evaluation photos (FaceEvalTest) faces turned 35 to 50 degrees
// scored 0.4 to 0.7 against their own person and under 0.35 against anyone else. Turned further, it's a profile.
private const val MAX_YAW = 50f
private const val MAX_PITCH = 30f

/**
 * Length of SFace's raw embedding below which the input was barely a face. The length grows with how clear and face-like
 * the picture is: on the evaluation photos every real face scored over 9, while the backs of heads, hands and smeared
 * faces that ML Kit took for faces scored 4 to 9, and those grouped with each other into a "person".
 */
private const val MIN_EMBEDDING_LENGTH = 9f

/**
 * Laplacian variance of the aligned 112 px face below which it's too blurred (or too small) to recognise. A sharp face
 * scores in the hundreds; the same face blurred (Gaussian, radius 2) or blown up from 30 px scores about 10 to 15.
 * Calibration knob: the indexing breadcrumb in crash reports logs how many faces it drops.
 */
private const val MIN_SHARPNESS = 25f

// ArcFace's 112 x 112 landmark template, as used by OpenCV's FaceRecognizerSF.alignCrop for SFace: eyes, then mouth corners,
// image-left first. Its nose point is left out: ML Kit marks the base of the nose, not the tip the template expects.
private val TEMPLATE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

/** A face worth recognising: where it is, how good a picture of it this is (0 to 1), and its unit-length embedding. */
class FoundFace(val box: FaceBox, val quality: Float, val embedding: FloatArray)

/**
 * Finds faces in a photo (ML Kit, bundled model) and turns each into an embedding (SFace on LiteRT, CPU, one thread).
 * Everything runs on the caller's thread and [executor]: give it the indexer's background-priority thread. Not thread-safe.
 */
class FaceAnalyser(private val context: Context, private val executor: Executor) : Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE) // better landmarks, so better alignment
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(MIN_FACE_PX.toFloat() / DECODE_PX)
            .setExecutor(executor)
            .build(),
    )
    private val model = try {
        Interpreter(loadModel(), Interpreter.Options().setNumThreads(1)) // no extra threads at normal priority
    } catch (e: Throwable) {
        detector.close() // close() is never reached when the constructor throws
        throw e
    }
    private val input = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(EMBEDDING) }
    private val aligned = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(INPUT * INPUT)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Faces found but not kept, by reason, for the timing breadcrumb: where the gates above need calibrating. */
    val dropped = sortedMapOf<String, Int>()

    /** The faces worth recognising in the photo at [uri] (maybe none), or null when it can't be read. */
    suspend fun analyse(uri: Uri): List<FoundFace>? {
        val photo = decode(uri) ?: return null
        try {
            return detector.facesIn(photo)?.mapNotNull { embed(photo, it) }
        } finally {
            photo.recycle()
        }
    }

    private fun decode(uri: Uri): Bitmap? = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val scale = DECODE_PX.toFloat() / max(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        // 10-bit HDR photos decode to other configs; ML Kit wants ARGB_8888
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    } catch (e: Exception) {
        null // corrupt, vanished or unsupported: saved with no faces so it isn't retried every pass
    }

    private fun embed(photo: Bitmap, face: Face): FoundFace? {
        val width = face.boundingBox.width()
        val yaw = face.headEulerAngleY
        val points = listOf(FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT)
            .map { face.getLandmark(it)?.position ?: return drop("no landmarks") }
        if (width < MIN_FACE_PX) return drop("small")
        if (abs(yaw) > MAX_YAW || abs(face.headEulerAngleX) > MAX_PITCH) return drop("turned")

        val t = similarity(alignmentPoints(points.flatMap { listOf(it.x, it.y) }.toFloatArray()), TEMPLATE)
        val matrix = Matrix().apply { setValues(floatArrayOf(t[0], -t[1], t[2], t[1], t[0], t[3], 0f, 0f, 1f)) }
        Canvas(aligned).apply {
            drawColor(Color.BLACK) // outside the photo stays black, as in OpenCV's warpAffine
            drawBitmap(photo, matrix, paint)
        }
        aligned.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        val sharpness = sharpness(pixels, INPUT, INPUT)
        if (sharpness < MIN_SHARPNESS) return drop("blurred")

        input.rewind()
        for (p in pixels) {
            input.putFloat((p shr 16 and 0xff).toFloat())
            input.putFloat((p shr 8 and 0xff).toFloat())
            input.putFloat((p and 0xff).toFloat())
        }
        input.rewind()
        model.run(input, output)
        if (length(output[0]) < MIN_EMBEDDING_LENGTH) return drop("unlike a face")
        val b = face.boundingBox
        val box = FaceBox(
            (b.left.toFloat() / photo.width).coerceIn(0f, 1f),
            (b.top.toFloat() / photo.height).coerceIn(0f, 1f),
            (b.right.toFloat() / photo.width).coerceIn(0f, 1f),
            (b.bottom.toFloat() / photo.height).coerceIn(0f, 1f),
        )
        return FoundFace(box, faceQuality(width, yaw, sharpness), unit(output[0]))
    }

    private fun drop(reason: String): FoundFace? {
        dropped.merge(reason, 1, Int::plus)
        return null
    }

    // Memory-mapped from the APK: the asset is stored uncompressed (AGP never compresses .tflite).
    private fun loadModel() = context.assets.openFd(MODEL_ASSET).use { fd ->
        FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }

    // ML Kit's Task as a suspend call; null when detection fails.
    private suspend fun FaceDetector.facesIn(bitmap: Bitmap): List<Face>? = suspendCancellableCoroutine { c ->
        process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(executor) { c.resume(if (it.isSuccessful) it.result else null) }
    }

    override fun close() {
        detector.close()
        model.close()
        aligned.recycle()
    }
}

/**
 * Orders two eye and two mouth-corner points (x, y pairs, either way round) as [TEMPLATE] wants: image-left eye,
 * image-right eye, image-left mouth corner, image-right mouth corner. "Left" is judged along the face's own
 * left-right axis (square to the line from the eyes to the mouth), so a tilted head can't swap them, and nothing
 * depends on whose left ML Kit means.
 */
fun alignmentPoints(p: FloatArray): FloatArray {
    // Down the face: from between the eyes to between the mouth corners. Rotated a quarter turn, it points to image-right.
    val downX = (p[4] + p[6] - p[0] - p[2]) / 2
    val downY = (p[5] + p[7] - p[1] - p[3]) / 2
    fun along(i: Int) = p[i] * downY - p[i + 1] * downX
    val eyes = if (along(0) <= along(2)) intArrayOf(0, 2) else intArrayOf(2, 0)
    val mouth = if (along(4) <= along(6)) intArrayOf(4, 6) else intArrayOf(6, 4)
    return (eyes + mouth).flatMap { listOf(p[it], p[it + 1]) }.toFloatArray()
}

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

/** 0 to 1: bigger, more frontal and sharper faces score higher. Picks each person's cover picture. */
fun faceQuality(widthPx: Int, yaw: Float, sharpness: Float): Float =
    min(1f, widthPx / 160f) * (1 - abs(yaw) / 90f) * min(1f, sharpness / 300f)

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

/** Coil model for a face picture. Requests key it by [faceId] (see FaceImage), so each face is cut once per session. */
data class FaceCrop(val faceId: Long, val uri: Uri, val box: FaceBox)

class FaceCropFetcher(private val crop: FaceCrop, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = options.size.width.pxOrElse { 256 }
        val resolver = options.context.contentResolver
        // Android's cached thumbnail is quickest; it's enough when the face is big in the photo. Otherwise decode just enough of it.
        val thumb = runCatching { resolver.loadThumbnail(crop.uri, Size(512, 512), null) }.getOrNull()
        val bitmap = thumb?.let { cut(it, px / 2) } ?: decode(px)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun cut(thumb: Bitmap, minPx: Int): Bitmap? {
        val (left, top, side) = avatarRect(crop.box, thumb.width, thumb.height)
        val face = if (side >= minPx) Bitmap.createBitmap(thumb, left, top, side, side) else null
        if (face !== thumb) thumb.recycle()
        return face
    }

    private fun decode(px: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(options.context.contentResolver, crop.uri)) { decoder, info, _ ->
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
