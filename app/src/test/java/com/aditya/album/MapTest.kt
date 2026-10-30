package com.aditya.album

import androidx.compose.ui.geometry.Offset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTest {
    private val tile = 768f // 256dp on a 3x screen
    private val calangute = Place("Calangute", "Goa", "IN", "India")

    private fun x(lng: Double) = mercatorX(lng)
    private fun y(lat: Double) = mercatorY(lat)

    // ---------- Projection ----------

    @Test fun projectionCornersAndRoundTrip() {
        assertEquals(0.0, x(-180.0), 1e-12)
        assertEquals(0.5, x(0.0), 1e-12)
        assertEquals(0.5, y(0.0), 1e-12)
        assertEquals(0.0, y(MAX_LAT), 1e-9)
        assertEquals(1.0, y(-MAX_LAT), 1e-9)
        for ((lat, lng) in listOf(15.5527 to 73.7517, -33.86 to 151.21, 64.13 to -21.9, -54.8 to -68.3)) {
            assertEquals(lat, latOf(y(lat)), 1e-9)
            assertEquals(lng, lngOf(x(lng)), 1e-9)
        }
    }

    @Test fun polesAreDrawnOnTheEdge() {
        assertEquals(0.0, y(90.0), 1e-9)
        assertEquals(1.0, y(-90.0), 1e-9)
    }

    @Test fun wrapTakesTheShortWayRound() {
        assertEquals(0.1, wrapDx(0.1), 1e-12)
        assertEquals(-0.1, wrapDx(0.9), 1e-12) // Fiji to Samoa: across the 180th meridian
        assertEquals(0.1, wrapDx(-0.9), 1e-12)
    }

    // ---------- Clustering ----------

    @Test fun nearbyPhotosShareAPinOnTheNewest() {
        // Newest first: two photos 10 m apart in Calangute, one in Mumbai
        val xs = doubleArrayOf(x(73.7517), x(73.7518), x(72.8777))
        val ys = doubleArrayOf(y(15.5527), y(15.5527), y(19.0760))
        val city = clusters(xs, ys, pinSpacing(10))
        assertEquals(2, city.size)
        assertEquals(listOf(0, 1), city[0].toList()) // the newest is the pin's own point
        assertEquals(listOf(2), city[1].toList())
        assertEquals(1, clusters(xs, ys, pinSpacing(2)).size) // the world: all in one pin
    }

    @Test fun everyPhotoIsInOnePinAndPinsNeverOverlap() {
        val random = java.util.Random(7)
        val n = 3_000
        val xs = DoubleArray(n) { x(70 + random.nextGaussian() * 5) }
        val ys = DoubleArray(n) { y(20 + random.nextGaussian() * 5) }
        for (level in listOf(0, 3, 6, 9, 12, 16)) {
            val radius = pinSpacing(level)
            val pins = clusters(xs, ys, radius)
            assertEquals((0 until n).toList(), pins.flatMap { it.toList() }.sorted())
            pins.forEach { pin -> pin.forEach { i -> assertTrue(distance(xs, ys, pin[0], i) <= radius + 1e-15) } }
            pins.forEach { pin -> assertTrue(pin.drop(1).all { it > pin[0] }) } // each pin sits on its newest photo
            val seeds = pins.map { it[0] }
            for (a in seeds.indices) for (b in a + 1 until seeds.size) assertTrue(distance(xs, ys, seeds[a], seeds[b]) > radius)
        }
    }

    @Test fun pinsJoinAcrossTheAntimeridian() {
        val xs = doubleArrayOf(x(179.999), x(-179.999))
        val ys = doubleArrayOf(y(-17.0), y(-17.0))
        assertEquals(1, clusters(xs, ys, pinSpacing(8)).size)
    }

    @Test fun samePinKeepsItsIdAsYouZoom() {
        val xs = doubleArrayOf(x(73.7517), x(73.80), x(72.8777))
        val ys = doubleArrayOf(y(15.5527), y(15.60), y(19.0760))
        val seeds = (0..16).map { level -> clusters(xs, ys, pinSpacing(level)).map { it[0] } }
        assertTrue(seeds.all { 0 in it }) // the newest photo's pin is there at every level
    }

    private fun distance(xs: DoubleArray, ys: DoubleArray, a: Int, b: Int): Double {
        val dx = wrapDx(xs[a] - xs[b])
        val dy = ys[a] - ys[b]
        return Math.sqrt(dx * dx + dy * dy)
    }

    // ---------- Bounds and camera ----------

    @Test fun boxAroundPhotos() {
        assertNull(boxOf(DoubleArray(0), DoubleArray(0)))
        val box = boxOf(doubleArrayOf(x(72.0), x(88.0)), doubleArrayOf(y(28.0), y(9.0)))!!
        assertEquals(x(72.0), box.left, 1e-12)
        assertEquals(x(88.0), box.right, 1e-12)
        assertEquals(x(80.0), box.centerX, 1e-12)
    }

    @Test fun boxCrossesTheAntimeridianWhenNarrower() {
        // Fiji and Samoa: 13 degrees apart across the 180th meridian, not 347 the other way
        val box = boxOf(doubleArrayOf(x(178.0), x(-171.0)), doubleArrayOf(y(-18.0), y(-14.0)))!!
        assertTrue(box.left > box.right)
        assertEquals(11.0 / 360, box.width, 1e-12)
        assertEquals(x(-176.5), box.centerX, 1e-12)
    }

    @Test fun fitShowsEveryPhoto() {
        val xs = doubleArrayOf(x(72.0), x(88.0), x(77.6))
        val ys = doubleArrayOf(y(28.0), y(9.0), y(13.0))
        val (w, h, pad) = Triple(1080f, 2000f, 120f)
        val cam = fit(boxOf(xs, ys)!!, w, h, tile, pad, 12.0)
        val worldPx = cam.worldPx(tile)
        for (i in xs.indices) {
            val sx = w / 2 + wrapDx(xs[i] - cam.x) * worldPx
            val sy = h / 2 + (ys[i] - cam.y) * worldPx
            assertTrue("x $sx", sx >= pad - 1 && sx <= w - pad + 1)
            assertTrue("y $sy", sy >= pad - 1 && sy <= h - pad + 1)
        }
    }

    @Test fun onePhotoFitsToACityNotAStreet() {
        val cam = fit(boxOf(doubleArrayOf(x(73.75)), doubleArrayOf(y(15.55)))!!, 1080f, 2000f, tile, 120f, 12.0)
        assertEquals(12.0, cam.zoom, 1e-9)
        assertEquals(x(73.75), cam.x, 1e-12)
    }

    @Test fun cameraStaysInsideTheWorld() {
        val (w, h) = 1080f to 2000f
        val out = MapCamera(1.25, 0.0, 0.0).clamped(w, h, tile)
        assertEquals(minZoom(w, h, tile), out.zoom, 1e-12)
        assertEquals(0.25, out.x, 1e-12) // wrapped
        assertEquals(0.5, out.y, 1e-12) // the world just fills the height, so it can't move up or down
        val deep = MapCamera(0.5, 0.0, 30.0).clamped(w, h, tile)
        assertEquals(MAX_ZOOM, deep.zoom, 1e-12)
        assertEquals(h / 2 / deep.worldPx(tile), deep.y, 1e-12) // the top edge, not past it
    }

    @Test fun pinchKeepsThePlaceUnderTheFingers() {
        val (w, h) = 1080f to 2000f
        val cam = MapCamera(x(77.6), y(13.0), 6.0)
        val about = Offset(300f, 700f)
        val before = cam.worldPx(tile)
        val wx = cam.x + (about.x - w / 2) / before
        val wy = cam.y + (about.y - h / 2) / before
        val zoomed = cam.transformed(Offset.Zero, 2f, about, w, h, tile)
        assertEquals(7.0, zoomed.zoom, 1e-9)
        val after = zoomed.worldPx(tile)
        assertEquals(about.x.toDouble(), w / 2 + (wx - zoomed.x) * after, 1e-3)
        assertEquals(about.y.toDouble(), h / 2 + (wy - zoomed.y) * after, 1e-3)
        val panned = cam.transformed(Offset(100f, 0f), 1f, about, w, h, tile)
        assertEquals(cam.x - 100 / before, panned.x, 1e-12) // the map follows the finger
    }

    // ---------- Pin albums ----------

    @Test fun mapAreaIdsRoundTripAndStayInTheirBand() {
        for (level in 0..16) for (seed in listOf(0L, 1L, 123_456L, Int.MAX_VALUE.toLong())) {
            val id = mapAreaIdOf(level, seed)
            assertTrue(isMapAreaId(id))
            assertEquals(level, mapAreaLevel(id))
            assertEquals(seed, mapAreaSeed(id))
            assertNull(collectionIdOf(id)) // collections start at 2^48
            assertNull(clusterOfAlbum(id)) // people end at 2^47
            assertFalse(isPlaceId(id))
            assertNull(thingOf(id))
        }
        val others = listOf(0L, -1L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE, albumIdOf(1), placeIdOf(calangute), thingIdOf(Category.entries.last())) +
            Special.entries.map { it.id }
        others.forEach { assertFalse(isMapAreaId(it)) }
    }

    @Test fun pinAlbumsAreNamedByTheirPlaces() {
        val fallback = "15.5527° N, 73.7517° E"
        val baga = calangute
        val panaji = Place("Panaji", "Goa", "IN", "India")
        val mumbai = Place("Mumbai", "Maharashtra", "IN", "India")
        val kathmandu = Place("Kathmandu", "Bagmati", "NP", "Nepal")
        val paris = Place("Paris", "Île-de-France", "FR", "France")
        val dubai = Place("Dubai", "Dubai", "AE", "United Arab Emirates")
        assertEquals(fallback, areaTitle(emptyList(), fallback)) // out at sea
        assertEquals("Calangute", areaTitle(listOf(baga, baga), fallback))
        assertEquals("Goa", areaTitle(listOf(baga, panaji), fallback))
        assertEquals("India", areaTitle(listOf(baga, mumbai), fallback))
        assertEquals("India and Nepal", areaTitle(listOf(kathmandu, baga, mumbai), fallback))
        assertEquals("India, Nepal and 2 more", areaTitle(listOf(baga, mumbai, kathmandu, kathmandu, paris, dubai), fallback))
    }

    // ---------- Map assets ----------

    private val assets = File("src/main/assets/map")

    @Test fun mapAssetsReadAndHaveLandWhereItShouldBe() {
        val levels = (0..2).map { readMapLevel(File(assets, "lod$it.bin").readBytes()) }
        assertEquals(setOf(LAND, COUNTRIES), levels[0].layers.keys)
        levels.drop(1).forEach { assertEquals(setOf(LAND, COUNTRIES, STATES), it.layers.keys) }
        val land = listOf(19.07 to 72.88, 12.97 to 77.59, 48.86 to 2.35, 39.74 to -104.99, -1.29 to 36.82, -23.7 to 133.88, 55.75 to 37.62)
        val sea = listOf(15.0 to 65.0, 0.0 to -30.0, -40.0 to -120.0, 10.0 to 88.0)
        levels.forEachIndexed { i, level ->
            land.forEach { (lat, lng) -> assertTrue("lod$i: $lat, $lng is land", isLand(level, x(lng), y(lat))) }
            sea.forEach { (lat, lng) -> assertFalse("lod$i: $lat, $lng is sea", isLand(level, x(lng), y(lat))) }
            level.layers.values.flatten().forEach { chunk ->
                assertTrue(chunk.left <= chunk.right && chunk.top <= chunk.bottom)
                chunk.parts.forEach { part ->
                    for (p in part.indices step 2) {
                        assertTrue(part[p] >= -1e-6 && chunk.left + part[p] <= chunk.right + 1e-6)
                        assertTrue(part[p + 1] >= -1e-6 && chunk.top + part[p + 1] <= chunk.bottom + 1e-6)
                    }
                }
            }
        }
    }

    @Test fun levelsOfDetailByZoom() {
        assertEquals(0, levelOfDetail(1.5))
        assertEquals(1, levelOfDetail(4.0))
        assertEquals(2, levelOfDetail(MAX_ZOOM))
    }

    /** Even-odd, over the squares a point is in (they overlap a little at their edges). */
    private fun isLand(level: MapLevel, x: Double, y: Double): Boolean = level.layers.getValue(LAND).any { chunk ->
        if (x < chunk.left || x > chunk.right || y < chunk.top || y > chunk.bottom) return@any false
        val px = (x - chunk.left).toFloat()
        val py = (y - chunk.top).toFloat()
        var inside = false
        for (part in chunk.parts) {
            var j = part.size - 2
            for (i in part.indices step 2) {
                if ((part[i + 1] > py) != (part[j + 1] > py) &&
                    px < (part[j] - part[i]) * (py - part[i + 1]) / (part[j + 1] - part[i + 1]) + part[i]
                ) inside = !inside
                j = i
            }
        }
        inside
    }
}
