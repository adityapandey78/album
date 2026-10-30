package com.aditya.album

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class DocumentScanTest {
    /** A [width] x [height] photo: [page] (corners in px, in order) filled with paper, lines of print on it, on a darker desk. */
    private fun photo(width: Int, height: Int, page: List<Offset>, paper: Int = 215, desk: Int = 70): IntArray {
        val random = Random(7)
        return IntArray(width * height) { i ->
            val p = Offset(i % width + 0.5f, i / width + 0.5f)
            val inside = page.indices.all { k -> cross(page[k], page[(k + 1) % 4], p) >= 0 }
            val print = inside && (i / width) % 9 < 2 && (i % width) % 13 < 9 // rows of dark words
            val base = when {
                print -> 40
                inside -> paper
                else -> desk
            }
            (base + random.nextInt(-12, 13)).coerceIn(0, 255)
        }
    }

    private fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun assertNear(expected: List<Offset>, actual: List<Offset>?, tolerance: Float) {
        checkNotNull(actual)
        expected.zip(actual).forEach { (e, a) -> assertTrue("$a should be near $e", (e - a).getDistance() <= tolerance) }
    }

    @Test fun findsATiltedPageOnADesk() {
        // A page photographed from above and a little to the side: narrower at the top, turned a few degrees
        val page = listOf(Offset(70f, 30f), Offset(180f, 40f), Offset(195f, 220f), Offset(50f, 210f))
        val found = guessPage(photo(256, 256, page), 256, 256)
        assertNear(page.map { Offset(it.x / 256, it.y / 256) }, found, tolerance = 0.03f)
    }

    @Test fun findsAPageTurnedFarRound() {
        // 30 degrees round its middle: the corners still come back top left first, clockwise
        val middle = Offset(128f, 100f)
        val a = Math.toRadians(30.0)
        val page = listOf(Offset(-70f, -50f), Offset(70f, -50f), Offset(70f, 50f), Offset(-70f, 50f)).map {
            Offset((it.x * cos(a) - it.y * sin(a)).toFloat(), (it.x * sin(a) + it.y * cos(a)).toFloat()) + middle
        }
        val found = guessPage(photo(256, 200, page), 256, 200)
        assertNear(ordered(page).map { Offset(it.x / 256, it.y / 200) }, found, tolerance = 0.03f)
    }

    @Test fun noPageInAPlainPhoto() {
        assertNull(guessPage(IntArray(64 * 64) { 128 }, 64, 64))
        // A small bright speck isn't a page
        assertNull(guessPage(photo(128, 128, listOf(Offset(10f, 10f), Offset(20f, 10f), Offset(20f, 20f), Offset(10f, 20f))), 128, 128))
    }

    @Test fun cornersComeInPageOrder() {
        val tl = Offset(0f, 0f)
        val tr = Offset(10f, 1f)
        val br = Offset(9f, 12f)
        val bl = Offset(-1f, 11f)
        assertEquals(listOf(tl, tr, br, bl), ordered(listOf(br, tl, bl, tr)))
    }

    @Test fun crossedCornersArentAPage() {
        assertTrue(isPageShape(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))))
        // Two corners swapped: the outline crosses itself
        assertFalse(isPageShape(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 10f), Offset(10f, 10f))))
        // One corner dragged inside: not convex
        assertFalse(isPageShape(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(3f, 3f), Offset(0f, 10f))))
        assertFalse(isPageShape(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f))))
    }

    @Test fun thePageTakesItsLongerSides() {
        // A4-ish page seen in perspective: the far (top) edge looks shorter, the near one keeps the width
        assertEquals(IntSize(420, 601), pageSize(listOf(Offset(40f, 0f), Offset(380f, 0f), Offset(420f, 600f), Offset(0f, 600f))))
        assertEquals(IntSize(100, 141), fitPixels(IntSize(1000, 1414), 14_140))
        assertEquals(IntSize(10, 10), fitPixels(IntSize(10, 10), 1_000))
    }

    @Test fun hullKeepsOnlyTheOutside() {
        val square = listOf(Offset(0f, 0f), Offset(4f, 0f), Offset(4f, 4f), Offset(0f, 4f))
        val hull = convexHull(square + listOf(Offset(2f, 2f), Offset(1f, 3f), Offset(2f, 0f)))
        assertEquals(square.toSet(), hull.toSet())
        assertEquals(16f, polygonArea(hull), 0.001f)
    }

    @Test fun otsuSplitsDarkFromBright() {
        val t = otsu(IntArray(100) { if (it < 40) 30 + it % 5 else 200 + it % 7 })
        assertTrue(t in 34 until 200)
    }

    @Test fun cleanUpEvensOutShadowsAndWhitensPaper() {
        // Paper lit from the right (120 on the left to 240 on the right), with ink a quarter as bright as the paper round it
        val w = 160
        val h = 120
        fun paperAt(x: Int) = 120 + 120 * x / (w - 1)
        fun ink(x: Int, y: Int) = y % 30 in 10..13 && x % 20 < 12
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val v = if (ink(x, i / w)) paperAt(x) / 4 else paperAt(x)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val levels = paperLevels(pixels, w, h)
        val colour = pixels.copyOf()
        clean(colour, w, 0, h, levels, mono = false)
        val mono = pixels.copyOf()
        for (top in listOf(0, 60)) { // in two bands of rows, as big pages are cleaned
            val band = mono.copyOfRange(top * w, (top + 60) * w)
            clean(band, w, top, h, levels, mono = true)
            band.copyInto(mono, top * w)
        }
        for (i in pixels.indices) {
            val x = i % w
            val y = i / w
            if (x < 2 || x >= w - 2) continue
            for (out in listOf(colour[i], mono[i])) {
                val v = out and 0xFF
                if (ink(x, y)) assertTrue("ink at $x,$y is $v", v <= 60) else if (!ink(x - 1, y) && !ink(x + 1, y)) assertTrue("paper at $x,$y is $v", v >= 245)
            }
        }
    }

    @Test fun copiesAreNamedAfterTheOriginal() {
        assertEquals("IMG_20241024_140512_scan.jpg", scanName("IMG_20241024_140512.jpg"))
        assertEquals("IMG_1_scan.jpg", scanName("IMG_1_scan (2).jpg"))
        assertEquals("Receipt_rotated_scan.jpg", scanName("Receipt_rotated.heic"))
        assertEquals("Photo_scan.jpg", scanName(".jpg"))
    }
}
