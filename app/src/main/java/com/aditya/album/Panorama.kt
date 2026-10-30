package com.aditya.album

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.tan

// Panoramas and 360° photos. A panorama is a photo at least twice as wide as it's tall (or three times as tall as it's
// wide): Pan in the viewer fills the screen with it to be dragged across, the page staying put. A 360° photo (a photo
// sphere) says so in its XMP, as Google's GPano, which phones and 360° cameras write: it opens in a view that looks around
// from inside the sphere, drawn by an AGSL shader on the GPU, so nothing new is bundled.

/** Which way a panorama runs. */
enum class Pano { Wide, Tall }

/**
 * Whether a [width] x [height] photo is a panorama. Tall ones need three times the width: phones' own screens are up to
 * about 2.6 times as tall as wide, so their screenshots, [screenshot] or not, never count.
 */
fun panoramaOf(width: Int, height: Int, screenshot: Boolean): Pano? = when {
    width <= 0 || height <= 0 || screenshot -> null
    width >= 2 * height -> Pano.Wide
    height >= 3 * width -> Pano.Tall
    else -> null
}

/**
 * The part of the whole sphere a 360° photo covers, in fractions of the sphere's width (360° round) and height (180° from
 * straight up to straight down): a photo sphere taken part of the way round covers less than all of it.
 */
data class Sphere(val left: Float, val top: Float, val width: Float, val height: Float)

/** The sphere a photo's [xmp] describes (GPano, equirectangular), or null when it isn't a 360° photo. */
fun sphereOf(xmp: String): Sphere? {
    if (!xmpValue(xmp, "ProjectionType").equals("equirectangular", ignoreCase = true)) return null
    fun px(name: String) = xmpValue(xmp, name)?.toFloatOrNull()
    val fullWidth = px("FullPanoWidthPixels")
    val fullHeight = px("FullPanoHeightPixels")
    val width = px("CroppedAreaImageWidthPixels")
    val height = px("CroppedAreaImageHeightPixels")
    // No cropped area given: the photo is the whole sphere
    if (fullWidth == null || fullHeight == null || width == null || height == null || fullWidth <= 0 || fullHeight <= 0) return Sphere(0f, 0f, 1f, 1f)
    val sphere = Sphere((px("CroppedAreaLeftPixels") ?: 0f) / fullWidth, (px("CroppedAreaTopPixels") ?: 0f) / fullHeight, width / fullWidth, height / fullHeight)
    val fits = sphere.width > 0 && sphere.height > 0 && sphere.top >= 0 && sphere.top + sphere.height <= 1.001f && sphere.width <= 1.001f
    return if (fits) sphere else Sphere(0f, 0f, 1f, 1f)
}

/** Most bytes read looking for the XMP: it follows the Exif block, which can be up to 64 KB with its thumbnail. */
private const val SPHERE_XMP_BYTES = 128 * 1024

// ponytail: one small entry per wide photo viewed, never trimmed; bound it if a session ever views tens of thousands.
private val spheres = mutableStateMapOf<Pair<Uri, Int>, Sphere?>()

/**
 * The sphere 360° photo [m] covers; null for other photos, and until its XMP has been read. Only photos at least 1.5
 * times as wide as tall are read: even a photo sphere taken part of the way round is.
 */
@Composable
fun rememberSphere(m: Media): Sphere? {
    val context = LocalContext.current
    val key = m.uri to editVersion(m.uri)
    LaunchedEffect(key) {
        if (m.isVideo || m.width < m.height * 3 / 2 || key in spheres) return@LaunchedEffect
        spheres[key] = withContext(Dispatchers.IO) {
            runCatching { readHead(context.contentResolver, m.uri)?.let(::sphereOf) }.onFailure { CrashLog.note("sphere check failed: $it") }.getOrNull()
        }
    }
    return spheres[key]
}

/** The start of [uri]'s file as text, one char per byte so binary data can't break the decode. */
private fun readHead(resolver: ContentResolver, uri: Uri): String? =
    resolver.openInputStream(uri)?.use { String(it.readNBytes(SPHERE_XMP_BYTES), Charsets.ISO_8859_1) }

// ---------- Looking around ----------

/** Longest side a 360° photo is decoded at: 32 MB, and as sharp as most phones' screens show a quarter of the way round. */
private const val SPHERE_PX = 4096

/** How much of the scene the screen's shorter side shows, in degrees: at first, and the most and least a pinch reaches. */
private const val FOV = 80f
private const val FOV_MIN = 30f
private const val FOV_MAX = 110f

/**
 * Each pixel on screen looks along a ray from the middle of the sphere: turned by [pitch] (up) and [yaw] (right), it hits
 * the sphere at a longitude and latitude, which is where it is on the equirectangular photo. [area] is the [Sphere].
 */
private const val SPHERE_SHADER = """
uniform shader image;
uniform float2 size;
uniform float2 imageSize;
uniform float yaw;
uniform float pitch;
uniform float focal;
uniform float4 area;

half4 main(float2 p) {
    float3 d = normalize(float3(p.x - size.x * 0.5, p.y - size.y * 0.5, focal));
    float cp = cos(pitch);
    float sp = sin(pitch);
    d = float3(d.x, d.y * cp - d.z * sp, d.y * sp + d.z * cp);
    float cy = cos(yaw);
    float sy = sin(yaw);
    d = float3(d.x * cy + d.z * sy, d.y, d.z * cy - d.x * sy);
    float u = atan(d.x, d.z) / 6.2831853 + 0.5;
    float v = asin(clamp(d.y, -1.0, 1.0)) / 3.1415927 + 0.5;
    float2 uv = (float2(u, v) - area.xy) / area.zw;
    if (uv.y < 0.0 || uv.y > 1.0) return half4(0.0, 0.0, 0.0, 1.0);
    if (area.z < 0.999) {
        if (uv.x < 0.0 || uv.x > 1.0) return half4(0.0, 0.0, 0.0, 1.0);
    } else {
        uv.x = fract(uv.x);
    }
    return image.eval(uv * imageSize);
}
"""

/**
 * A 360° photo seen from inside: drag to look around (it keeps turning a little after a fling, unless Remove animations
 * is on), pinch to see more or less of it. Opens facing the middle of the photo. [onClose] for its Close button; Back is
 * the viewer's.
 */
@Composable
fun SphereView(m: Media, sphere: Sphere, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val scope = rememberCoroutineScope()
    val image by produceState<Bitmap?>(null, m.uri) {
        value = withContext(Dispatchers.IO) { decodeUpright(context, m.uri, SPHERE_PX) }
    }
    val shader = remember { RuntimeShader(SPHERE_SHADER) }
    // Radians: 0, 0 faces the middle of the photo
    var yaw by remember(sphere) { mutableFloatStateOf(((sphere.left + sphere.width / 2 - 0.5f) * 2 * PI).toFloat()) }
    var pitch by remember(sphere) { mutableFloatStateOf((-(sphere.top + sphere.height / 2 - 0.5f) * PI).toFloat()) }
    var fov by remember { mutableFloatStateOf(FOV) }
    var side by remember { mutableFloatStateOf(1f) } // the screen's shorter side, px
    // Px from the eye to the screen, for the screen's size and [fov]
    fun focal() = side / 2 / tan(Math.toRadians(fov / 2.0)).toFloat()
    // Content follows the finger: dragging right turns to look left
    fun look(by: Offset) {
        yaw -= by.x / focal()
        pitch = (pitch + by.y / focal()).coerceIn(-PI.toFloat() / 2, PI.toFloat() / 2)
    }
    Box(modifier.fillMaxSize().background(c.void).semantics { contentDescription = "360° photo. Drag to look around." }) {
        val bitmap = image
        if (bitmap != null) {
            // Wraps round left to right, where the sphere's two edges meet
            val photo = remember(bitmap) { BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR } }
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { side = min(it.width, it.height).toFloat() }
                    .pointerInput(Unit) {
                        var fling: Job? = null
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fling?.cancel()
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var pinched = false // a pinch doesn't fling when it ends
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1) pinched = true
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) fov = (fov / zoom).coerceIn(FOV_MIN, FOV_MAX)
                                look(event.calculatePan())
                                event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            val velocity = tracker.calculateVelocity()
                            val speed = Offset(velocity.x, velocity.y).getDistance()
                            if (!still && !pinched && speed > 0f) {
                                val direction = Offset(velocity.x / speed, velocity.y / speed)
                                fling = scope.launch {
                                    var last = 0f
                                    animateDecay(0f, speed, FloatExponentialDecaySpec()) { value, _ ->
                                        look(direction * (value - last))
                                        last = value
                                    }
                                }
                            }
                        }
                    }
                    .drawBehind {
                        shader.setInputShader("image", photo)
                        shader.setFloatUniform("size", size.width, size.height)
                        shader.setFloatUniform("imageSize", bitmap.width.toFloat(), bitmap.height.toFloat())
                        shader.setFloatUniform("yaw", yaw)
                        shader.setFloatUniform("pitch", pitch)
                        shader.setFloatUniform("focal", focal())
                        shader.setFloatUniform("area", sphere.left, sphere.top, sphere.width, sphere.height)
                        drawRect(ShaderBrush(shader))
                    },
            )
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.scrimPhoto, Color.Transparent)))
                .statusBarsPadding()
                .padding(start = Space.s2, end = Space.s2, top = Space.s1, bottom = Space.s8),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumIconButton(Sym.Close, "Close", onClose, variant = IconButtonVariant.Photo)
            Column {
                Text("360° photo", style = AlbumTheme.type.label, color = c.onPhoto)
                Text("Drag to look around", style = AlbumTheme.type.caption, color = c.onPhoto)
            }
        }
    }
}
