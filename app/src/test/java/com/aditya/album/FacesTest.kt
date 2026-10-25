package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FacesTest {
    // Eyes and mouth corners of an upright face: image-left eye, image-right eye, image-left mouth, image-right mouth
    private val upright = floatArrayOf(100f, 100f, 140f, 100f, 105f, 150f, 135f, 150f)

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

    @Test fun landmarksAreOrderedImageLeftFirstWhicheverWayRoundTheyCome() {
        val swapped = floatArrayOf(140f, 100f, 100f, 100f, 135f, 150f, 105f, 150f)
        assertArrayEquals(upright, alignmentPoints(swapped), 0f)
        assertArrayEquals(upright, alignmentPoints(upright), 0f)
    }

    @Test fun anUpsideDownFaceIsTurnedUpright() {
        // The face rotated half a turn: aligned onto the template, its left eye must land on the template's left eye
        val flipped = FloatArray(8) { if (it % 2 == 0) 300 - upright[it] else 300 - upright[it] }
        val points = alignmentPoints(flipped)
        val template = floatArrayOf(38.29f, 51.70f, 73.53f, 51.50f, 41.55f, 92.37f, 70.73f, 92.20f)
        val aligned = transform(similarity(points, template), points)
        assertArrayEquals(template, aligned, 2f)
    }

    @Test fun sharpnessTellsDetailFromBlur() {
        val flat = IntArray(16 * 16) { 0xff808080.toInt() }
        val checks = IntArray(16 * 16) { if ((it % 16 + it / 16) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
        assertEquals(0f, sharpness(flat, 16, 16), 1e-3f)
        assertTrue(sharpness(checks, 16, 16) > 1000f)
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
        assertTrue(faceQuality(200, 0f, 400f) > faceQuality(60, 0f, 400f))
        assertTrue(faceQuality(200, 0f, 400f) > faceQuality(200, 30f, 400f))
        assertTrue(faceQuality(200, 0f, 400f) > faceQuality(200, 0f, 40f))
    }
}
