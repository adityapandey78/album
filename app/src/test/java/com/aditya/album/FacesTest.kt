package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

class FacesTest {
    // Eyes, nose tip and mouth corners of an upright face, image-left first
    private val upright = floatArrayOf(100f, 100f, 140f, 100f, 120f, 125f, 105f, 150f, 135f, 150f)

    private fun transform(t: FloatArray, p: FloatArray) = FloatArray(p.size) { i ->
        val x = p[i - i % 2]
        val y = p[i - i % 2 + 1]
        if (i % 2 == 0) t[0] * x - t[1] * y + t[2] else t[1] * x + t[0] * y + t[3]
    }

    @Test fun similarityRecoversScaleRotationAndShift() {
        val angle = Math.toRadians(30.0)
        val expected = floatArrayOf((2 * cos(angle)).toFloat(), (2 * sin(angle)).toFloat(), 15f, -7f)
        val fitted = similarity(upright, transform(expected, upright))
        assertArrayEquals(expected, fitted, 1e-3f)
    }

    /** YuNet outputs for a [side] x [side] input, all zero but the anchors given: stride, index, scores, box, landmarks. */
    private fun outputs(side: Int, vararg anchors: Pair<Pair<Int, Int>, FloatArray>): List<FloatArray> {
        val strides = intArrayOf(8, 16, 32)
        val sizes = strides.map { (side / it) * (side / it) }
        val out = listOf(1, 1, 4, 10).flatMap { k -> sizes.map { FloatArray(it * k) } }
        for ((at, v) in anchors) {
            val (s, index) = at
            val i = strides.indexOf(s)
            out[i][index] = v[0] // cls
            out[3 + i][index] = v[1] // obj
            for (k in 0 until 4) out[6 + i][4 * index + k] = v[2 + k]
            for (k in 0 until 10) out[9 + i][10 * index + k] = v[6 + k]
        }
        return out
    }

    @Test fun yunetOutputsDecodeAsOpenCvDoes() {
        // Stride 16 on a 64 x 64 input: 4 x 4 anchors; anchor 6 is column 2, row 1
        val face = floatArrayOf(0.9f, 0.9f, 0.5f, 0.5f, ln(2f), ln(3f)) + FloatArray(10) { if (it % 2 == 0) -0.25f else 0.25f }
        val found = yunetFaces(outputs(64, (16 to 6) to face), 64, 64, minScore = 0.6f).single()
        assertEquals(0.9f, found.score, 1e-6f) // sqrt(cls * obj)
        // Centre (2.5 * 16, 1.5 * 16) = (40, 24), 32 x 48; pixel centres at +0.5
        assertArrayEquals(floatArrayOf(24.5f, 0.5f, 56.5f, 48.5f), floatArrayOf(found.left, found.top, found.right, found.bottom), 1e-4f)
        assertArrayEquals(FloatArray(10) { if (it % 2 == 0) 28.5f else 20.5f }, found.points, 1e-4f) // (2 - 0.25) * 16, (1 + 0.25) * 16
    }

    @Test fun weakAndOverlappingDetectionsGo() {
        val strong = floatArrayOf(0.95f, 0.95f, 0f, 0f, 0f, 0f) + FloatArray(10)
        val beside = floatArrayOf(0.8f, 0.8f, 0.1f, 0f, 0f, 0f) + FloatArray(10) // the same face a little to the right
        val faint = floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f, 0f) + FloatArray(10)
        val found = yunetFaces(outputs(64, (8 to 9) to strong, (8 to 10) to beside.also { it[2] = -0.9f }, (8 to 40) to faint), 64, 64, minScore = 0.6f)
        assertEquals(listOf(0.95f), found.map { it.score }) // overlapping one merged, faint one dropped
    }

    @Test fun affineMapsComposeAndInvert() {
        val a = floatArrayOf(2f, 0f, 5f, 0f, 3f, -1f)
        val b = floatArrayOf(0f, -1f, 10f, 1f, 0f, 0f)
        val (x, y) = affineMap(affineThen(a, b), 1f, 2f)
        val (ax, ay) = affineMap(a, 1f, 2f)
        assertEquals(affineMap(b, ax, ay), x to y)
        val back = affineMap(affineInverse(affineThen(a, b)), x, y)
        assertEquals(1f, back.first, 1e-5f)
        assertEquals(2f, back.second, 1e-5f)
    }

    /**
     * A 3 x 2 stored picture turned upright by each EXIF orientation: every stored pixel's centre must land on the
     * centre of the upright pixel EXIF says, so a face cut from stored pixels is the one found in the upright photo.
     */
    @Test fun storedPixelsLandWhereExifPutsThem() {
        val stored = arrayOf(intArrayOf(1, 2, 3), intArrayOf(4, 5, 6)) // rows, top first
        // Each orientation's upright picture, as EXIF defines it
        val expected = mapOf(
            1 to arrayOf(intArrayOf(1, 2, 3), intArrayOf(4, 5, 6)),
            2 to arrayOf(intArrayOf(3, 2, 1), intArrayOf(6, 5, 4)),
            3 to arrayOf(intArrayOf(6, 5, 4), intArrayOf(3, 2, 1)),
            4 to arrayOf(intArrayOf(4, 5, 6), intArrayOf(1, 2, 3)),
            5 to arrayOf(intArrayOf(1, 4), intArrayOf(2, 5), intArrayOf(3, 6)),
            6 to arrayOf(intArrayOf(4, 1), intArrayOf(5, 2), intArrayOf(6, 3)),
            7 to arrayOf(intArrayOf(6, 3), intArrayOf(5, 2), intArrayOf(4, 1)),
            8 to arrayOf(intArrayOf(3, 6), intArrayOf(2, 5), intArrayOf(1, 4)),
        )
        for ((orientation, picture) in expected) {
            val m = uprightFromStored(orientation, 3, 2)
            for (y in 0 until 2) for (x in 0 until 3) {
                val (ux, uy) = affineMap(m, x + 0.5f, y + 0.5f)
                assertEquals("orientation $orientation, stored ($x, $y)", stored[y][x], picture[(uy - 0.5f).toInt()][(ux - 0.5f).toInt()])
            }
        }
    }

    @Test fun headTurnIsZeroFacingTheCameraAndGrowsWithTheTurn() {
        assertEquals(0f, headTurn(upright), 1e-6f)
        val turned = upright.copyOf().also { it[4] += 12f } // nose tip 12 px aside, eyes 40 apart
        assertEquals(0.3f, kotlin.math.abs(headTurn(turned)), 1e-4f)
        val other = upright.copyOf().also { it[4] -= 12f }
        assertEquals(-headTurn(turned), headTurn(other), 1e-6f)
        // The same face rotated in the picture turns the same amount
        val rotated = transform(floatArrayOf(cos(1f), sin(1f), 0f, 0f), turned)
        assertEquals(headTurn(turned), headTurn(rotated), 1e-4f)
    }

    @Test fun sharpnessTellsDetailFromBlur() {
        val flat = IntArray(16 * 16) { 0xff808080.toInt() }
        val checks = IntArray(16 * 16) { if ((it % 16 + it / 16) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
        assertEquals(0f, sharpness(flat, 16, 16), 1e-3f)
        assertTrue(sharpness(checks, 16, 16) > 1000f)
    }

    @Test fun brightnessRunsFromBlackToWhite() {
        assertEquals(0f, brightness(IntArray(4) { 0xff000000.toInt() }), 1e-6f)
        assertEquals(1f, brightness(IntArray(4) { 0xffffffff.toInt() }), 1e-6f)
        assertEquals(0.5f, brightness(intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())), 1e-6f)
    }

    @Test fun avatarIsASquareAroundTheFaceInsideThePicture() {
        assertArrayEquals(intArrayOf(68, 68, 64), avatarRect(FaceBox(0.4f, 0.4f, 0.6f, 0.6f), 200, 200)) // 40 px face, 1.6 times
        // A face at the very edge still gets a full square, moved inside
        val (left, top, side) = avatarRect(FaceBox(0.9f, 0f, 1f, 0.1f), 200, 100)
        assertEquals(32, side)
        assertEquals(200 - 32, left)
        assertEquals(0, top)
        // Never bigger than the picture
        assertEquals(100, avatarRect(FaceBox(0f, 0f, 1f, 1f), 200, 100)[2])
    }

    @Test fun embeddingsSurviveTheDatabase() {
        val e = floatArrayOf(0.5f, -0.25f, 1e-7f, 0f)
        assertArrayEquals(e, e.toBytes().toFloats(), 0f)
    }

    @Test fun betterFacesScoreHigher() {
        fun q(eyes: Float = 60f, sharpness: Float = 400f, turn: Float = 0f, roll: Float = 0f, brightness: Float = 0.5f, score: Float = 0.9f, alone: Boolean = true) =
            faceQuality(eyes, sharpness, turn, roll, brightness, score, alone)
        val best = q()
        assertEquals(0.9f, best, 1e-6f)
        listOf(q(eyes = 20f), q(sharpness = 40f), q(turn = 0.3f), q(roll = 30f), q(brightness = 0.1f), q(score = 0.7f), q(alone = false)).forEach { assertTrue(it < best) }
        assertTrue(listOf(q(turn = 1f), q(eyes = 0f)).all { it == 0f })
    }
}
