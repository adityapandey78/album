package com.aditya.album

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh

// The offline map's pure parts: where a point goes (Web Mercator), how photos gather into pins, how the camera fits them,
// and reading the map assets tools/natural_earth.py writes. MapScreen.kt draws them.
//
// World units: the world as a square from (0, 0) at 180° W, 85.05° N to (1, 1) at 180° E, 85.05° S. x wraps at the
// 180th meridian. At zoom z the world is 256dp × 2^z wide, as on web maps, so Natural Earth's zoom hints carry over.

/** Web Mercator's square stops here, both ways; a photo nearer a pole is drawn on the edge. */
const val MAX_LAT = 85.0511287798

/** Map zoom: the world 256dp wide at 0 and 16.8 million dp at 16, about 150 m across a pin. */
const val MAX_ZOOM = 16.0

/** A pin's size, and the least distance between two pins at their level's zoom: they never overlap. */
const val PIN_DP = 56f
const val PIN_SPACING_DP = 64f
const val WORLD_DP = 256f

fun mercatorX(lng: Double) = (lng + 180) / 360

fun mercatorY(lat: Double): Double {
    val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

fun lngOf(x: Double) = wrapped(x) * 360 - 180

fun latOf(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

/** x brought back into the world, 0 to 1, after going round it. */
fun wrapped(x: Double) = x - floor(x)

/** The shorter way along x from one point to another, -0.5 to 0.5: across the 180th meridian when that's nearer. */
fun wrapDx(dx: Double) = dx - round(dx)

/**
 * Gathers points into pins at least [radius] apart (world units). Points go in order (newest first): each joins the
 * nearest pin within [radius], or starts one of its own. So a pin sits on its newest photo, which is its cover, the same
 * pin keeps its place as you zoom, and no two pins are closer than [radius]. Returns each pin's points by index, its own
 * first. Linear: pins are bucketed into cells at least [radius] wide, so a point only measures the pins in nine cells.
 */
fun clusters(xs: DoubleArray, ys: DoubleArray, radius: Double): List<IntArray> {
    // Whole cells across, so the last column is as wide as the rest and the wrap at the 180th meridian is one cell over
    val cols = floor(1 / radius).toInt().coerceAtLeast(1)
    val rows = cols
    fun col(x: Double) = Math.floorMod(floor(x * cols).toInt(), cols)
    fun row(y: Double) = floor(y * rows).toInt().coerceIn(0, rows - 1)
    val seedsIn = HashMap<Int, MutableList<Int>>() // cell to the pins whose point is in it
    val pins = ArrayList<MutableList<Int>>()
    val pinOf = HashMap<Int, Int>() // a pin's own point to the pin
    val r2 = radius * radius
    for (i in xs.indices) {
        val c = col(xs[i])
        val r = row(ys[i])
        var best = -1
        var bestD = r2
        for (dc in -1..1) for (dr in -1..1) {
            if (r + dr !in 0 until rows) continue
            seedsIn[Math.floorMod(c + dc, cols) * rows + r + dr]?.forEach { s ->
                val dx = wrapDx(xs[i] - xs[s])
                val dy = ys[i] - ys[s]
                val d = dx * dx + dy * dy
                if (d <= bestD) {
                    bestD = d
                    best = s
                }
            }
        }
        if (best >= 0) {
            pins[pinOf.getValue(best)] += i
        } else {
            pinOf[i] = pins.size
            pins += mutableListOf(i)
            seedsIn.getOrPut(c * rows + r) { ArrayList(2) } += i
        }
    }
    return pins.map { it.toIntArray() }
}

/** How far apart pins are at a whole zoom level, in world units. */
fun pinSpacing(level: Int) = PIN_SPACING_DP.toDouble() / (WORLD_DP * (1L shl level))

/**
 * A box on the map. [left] is greater than [right] when it crosses the 180th meridian (Fiji to Samoa); [width] is then
 * the part across it.
 */
data class WorldBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = if (right >= left) right - left else right + 1 - left
    val height get() = bottom - top
    val centerX get() = wrapped(left + width / 2)
    val centerY get() = (top + bottom) / 2
}

/** The smallest box around the points, across the 180th meridian when that's narrower. Null for no points. */
fun boxOf(xs: DoubleArray, ys: DoubleArray): WorldBox? {
    if (xs.isEmpty()) return null
    val sorted = xs.map(::wrapped).sorted()
    // The box leaves out the widest gap between neighbouring points, going round the world: by default the one across
    // the 180th meridian, from the last point back to the first
    var gap = sorted.first() + 1 - sorted.last()
    var left = sorted.first()
    var right = sorted.last()
    for (i in 1 until sorted.size) {
        val g = sorted[i] - sorted[i - 1]
        if (g > gap) {
            gap = g
            left = sorted[i]
            right = sorted[i - 1]
        }
    }
    return WorldBox(left, ys.min(), right, ys.max())
}

/** Where the map looks: the world point at the middle of the screen, and the zoom. */
data class MapCamera(val x: Double, val y: Double, val zoom: Double) {
    /** The world's width on screen, in pixels, for [tile] pixels in 256dp. */
    fun worldPx(tile: Float) = tile * Math.pow(2.0, zoom)
}

/** The least zoom for a screen [width] by [height]: the world at least fills it, so it never shows twice side by side. */
fun minZoom(width: Float, height: Float, tile: Float) = log2(maxOf(width, height, 1f).toDouble() / tile)

/**
 * The camera kept inside the world: zoomed between [minZoom] and [MAX_ZOOM], x wrapped to 0 to 1, and y no further
 * than the world's top and bottom edges allow.
 */
fun MapCamera.clamped(width: Float, height: Float, tile: Float): MapCamera {
    val zoom = zoom.coerceIn(minZoom(width, height, tile), MAX_ZOOM)
    val half = height / 2 / (tile * Math.pow(2.0, zoom))
    return MapCamera(wrapped(x), if (half >= 0.5) 0.5 else y.coerceIn(half, 1 - half), zoom)
}

/**
 * The camera that shows all of [box] with [pad] pixels to spare on every side, no closer than [maxZoom] (a city: one
 * photo, or a few in one street, shouldn't fill the screen with land).
 */
fun fit(box: WorldBox, width: Float, height: Float, tile: Float, pad: Float, maxZoom: Double): MapCamera {
    val w = (width - 2 * pad).coerceAtLeast(1f)
    val h = (height - 2 * pad).coerceAtLeast(1f)
    val worldPx = min(if (box.width > 0) w / box.width else Double.MAX_VALUE, if (box.height > 0) h / box.height else Double.MAX_VALUE)
    val zoom = if (worldPx == Double.MAX_VALUE) maxZoom else min(log2(worldPx / tile), maxZoom)
    return MapCamera(box.centerX, box.centerY, zoom).clamped(width, height, tile)
}

/**
 * Zooms by [factor] about a screen point, which stays over the same place, then moves by [pan] pixels. [width] and
 * [height] are the screen's.
 */
fun MapCamera.transformed(pan: Offset, factor: Float, about: Offset, width: Float, height: Float, tile: Float): MapCamera {
    val worldPx = worldPx(tile)
    val wx = x + (about.x - width / 2) / worldPx
    val wy = y + (about.y - height / 2) / worldPx
    val zoom = (zoom + log2(factor.toDouble())).coerceIn(minZoom(width, height, tile), MAX_ZOOM)
    val after = tile * Math.pow(2.0, zoom)
    return MapCamera(wx - (about.x + pan.x - width / 2) / after, wy - (about.y + pan.y - height / 2) / after, zoom).clamped(width, height, tile)
}

// ---------- Opening a pin ----------

/**
 * A pin opens as an album through an id, like places and things (see Explore.kt), so it gets the album screen, its
 * selection and a viewer that swipes through just its photos. Its own band, between people (2^46 up to 2^47) and
 * collections (2^48 and up): 2^47, plus the zoom level, plus the media id of the pin's newest photo, which finds the pin
 * again from the same photos.
 */
private const val MAP_IDS = 1L shl 47
private const val SEED_BITS = 40
private const val SEED_MASK = (1L shl SEED_BITS) - 1

fun mapAreaIdOf(level: Int, seedId: Long) = MAP_IDS + (level.toLong() shl SEED_BITS) + (seedId and SEED_MASK)

fun isMapAreaId(id: Long) = id >= MAP_IDS && id < MAP_IDS + ((MAX_ZOOM.toLong() + 1) shl SEED_BITS)

fun mapAreaLevel(id: Long) = ((id - MAP_IDS) shr SEED_BITS).toInt()

fun mapAreaSeed(id: Long) = (id - MAP_IDS) and SEED_MASK

/**
 * What a pin's album is called, from its photos' places: the town when they're all in one ("Calangute"), else the state
 * ("Goa"), else the country, else the countries with most photos ("India and Nepal", "France, Italy and 3 more").
 * [fallback] (the pin's coordinates) when none of its photos has a place: out at sea, or far from any town.
 */
fun areaTitle(places: List<Place>, fallback: String): String {
    if (places.isEmpty()) return fallback
    val first = places[0]
    return when {
        places.all { it.city == first.city && it.admin1 == first.admin1 && it.countryCode == first.countryCode } -> first.city
        first.admin1.isNotEmpty() && places.all { it.admin1 == first.admin1 && it.countryCode == first.countryCode } -> first.admin1
        places.all { it.countryCode == first.countryCode } -> first.country
        else -> {
            val countries = places.groupingBy { it.country }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            if (countries.size == 2) "${countries[0]} and ${countries[1]}" else "${countries[0]}, ${countries[1]} and ${countries.size - 2} more"
        }
    }
}

// ---------- Map assets ----------

/** The kinds of layer in a map asset, as tools/natural_earth.py numbers them. */
const val LAND = 0
const val COUNTRIES = 1
const val STATES = 2

/** One square of a layer: its points' bounds (world units) and its parts, each x, y, x, y... from (left, top). */
class MapChunk(val left: Double, val top: Double, val right: Double, val bottom: Double, val parts: List<FloatArray>)

/** One level of detail: its layers by kind. */
class MapLevel(val layers: Map<Int, List<MapChunk>>)

/** Reads an asset tools/natural_earth.py wrote (its format is described there). */
fun readMapLevel(bytes: ByteArray): MapLevel {
    var at = 0
    fun varint(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val b = bytes[at++].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return v
            shift += 7
        }
    }
    fun zigzag() = varint().let { (it ushr 1) xor -(it and 1) }
    check(String(bytes, 0, 4, Charsets.US_ASCII) == "AMAP" && bytes[4].toInt() == 1) { "not a map level" }
    val grid = (1 shl 24).toDouble()
    at = 6
    val layers = HashMap<Int, List<MapChunk>>()
    repeat(bytes[5].toInt()) {
        val kind = bytes[at++].toInt()
        layers[kind] = List(varint().toInt()) {
            val left = varint()
            val top = varint()
            val right = varint()
            val bottom = varint()
            val parts = List(varint().toInt()) {
                val part = FloatArray(varint().toInt() * 2)
                var x = 0L
                var y = 0L
                for (i in part.indices step 2) {
                    x += zigzag()
                    y += zigzag()
                    part[i] = (x / grid).toFloat()
                    part[i + 1] = (y / grid).toFloat()
                }
                part
            }
            MapChunk(left / grid, top / grid, right / grid, bottom / grid, parts)
        }
    }
    check(at == bytes.size) { "map level has ${bytes.size - at} bytes left over" }
    return MapLevel(layers)
}

/** Which level of detail suits a zoom: the world, a region, or a country and closer (see tools/natural_earth.py). */
fun levelOfDetail(zoom: Double) = if (zoom < 3) 0 else if (zoom < 6) 1 else 2
