package com.aditya.album

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ViewerTest {
    private fun swipe(offset: Float, velocity: Float) = swipeOf(offset, velocity, distance = 100f, speed = 1000f)

    @Test fun shortSlowDragSpringsBack() {
        assertEquals(Swipe.None, swipe(50f, 200f))
        assertEquals(Swipe.None, swipe(-50f, -200f))
    }

    @Test fun longPullDownClosesAndUpOpensDetails() {
        assertEquals(Swipe.Close, swipe(150f, 0f))
        assertEquals(Swipe.Details, swipe(-150f, 0f))
    }

    @Test fun quickFlickIsEnoughEvenWhenShort() {
        assertEquals(Swipe.Close, swipe(20f, 1500f))
        assertEquals(Swipe.Details, swipe(-20f, -1500f))
    }

    @Test fun flingingBackCancels() {
        assertEquals(Swipe.None, swipe(150f, -1500f)) // pulled down far, then flicked up
        assertEquals(Swipe.None, swipe(-150f, 1500f))
        assertEquals(Swipe.None, swipe(-20f, 1500f)) // a downward flick never opens details
    }
}

class ViewerWordsTest {
    private val today = LocalDate.of(2026, 10, 25).toEpochDay()

    @Test fun daysAreNamedLikeTheDesign() {
        assertEquals("Today", dayTitle(today, today))
        assertEquals("Yesterday", dayTitle(today - 1, today))
        assertEquals("Sat, 24 Oct", dayTitle(LocalDate.of(2026, 10, 24).toEpochDay(), today.plus(1))) // two days ago
        assertEquals("Fri, 24 Oct 2025", dayTitle(LocalDate.of(2025, 10, 24).toEpochDay(), today)) // another year
    }

    @Test fun cameraNameSaysTheMakerOnce() {
        assertEquals("motorola edge 70 fusion", cameraName("motorola", "motorola edge 70 fusion"))
        assertEquals("Google Pixel 9", cameraName("Google", "Pixel 9"))
        assertEquals("Pixel 9", cameraName(null, " Pixel 9 "))
        assertEquals("Canon", cameraName("Canon", ""))
        assertNull(cameraName(null, null))
    }

    @Test fun exifLineReadsLikeACameraDisplay() {
        assertEquals("ƒ/1.8  1/120 s  ISO 200  24 mm", exifLine(1.8, 1 / 120.0, 200, 24.0))
        assertEquals("ƒ/2  2 s  4.7 mm", exifLine(2.0, 2.0, 0, 4.71)) // a long exposure; no ISO written
        assertEquals("1/3 s", exifLine(0.0, 0.3333, 0, 0.0))
        assertNull(exifLine(0.0, 0.0, 0, 0.0)) // screenshots, downloads
    }

    @Test fun coordinatesSayTheHemisphere() {
        assertEquals("12.9716° N, 77.5946° E", coordinates(12.97163, 77.59459))
        assertEquals("33.8688° S, 151.2093° W", coordinates(-33.8688, -151.2093))
    }

    @Test fun fileFormatComesFromTheNameThenTheType() {
        assertEquals("JPEG", fileFormat("IMG_20261024_1405.jpg", "image/jpeg"))
        assertEquals("HEIC", fileFormat("IMG_1.heic", null))
        assertEquals("MP4", fileFormat("VID", "video/mp4")) // a name from another app, without an extension
        assertNull(fileFormat("VID", null))
    }
}

class TwistTest {
    @Test fun aSmallTwistDoesNothingThenFollowsWithoutAJump() {
        assertEquals(0f, twistAngle(10f), 0f) // pinching twists a little
        assertEquals(0f, twistAngle(-15f), 0f)
        assertEquals(1f, twistAngle(16f), 0.001f)
        assertEquals(-90f, twistAngle(-105f), 0.001f)
    }

    @Test fun releaseSettlesOnTheNearestQuarter() {
        assertEquals(0, nearestQuarter(44f))
        assertEquals(1, nearestQuarter(46f))
        assertEquals(-1, nearestQuarter(-80f))
        assertEquals(2, nearestQuarter(200f))
        assertEquals(4, nearestQuarter(350f)) // a full turn: the viewer writes nothing
    }

    @Test fun ticksOnReachingEachQuarterButNotOnLeavingOne() {
        assertEquals(true, reachesQuarter(89f, 90f))
        assertEquals(true, reachesQuarter(91f, 89f)) // back through it
        assertEquals(true, reachesQuarter(-89f, -91f))
        assertEquals(true, reachesQuarter(1f, -1f)) // back to upright
        assertEquals(false, reachesQuarter(90f, 91f)) // already ticked at 90
        assertEquals(false, reachesQuarter(0f, -1f)) // the twist starts at 0
        assertEquals(false, reachesQuarter(10f, 80f))
    }

    @Test fun aTurnedPhotoFitsAndEndsAtItsRotatedSize() {
        val box = Size(1080f, 2400f)
        val landscape = fitRect(4000, 3000, box).size // 1080 x 810
        assertEquals(1f, twistScale(landscape, box, 0f), 0.001f)
        // a quarter turn: exactly the size the rotated (now portrait) photo is drawn at once it reloads
        val portrait = fitRect(3000, 4000, box).size
        assertEquals(portrait.width / landscape.height, twistScale(landscape, box, 90f), 0.001f)
        assertEquals(1f, twistScale(landscape, box, 180f), 0.001f)
        // a square at 45° needs √2 of the room, so it shrinks to fit
        assertEquals(1 / Math.sqrt(2.0).toFloat(), twistScale(Size(100f, 100f), Size(100f, 100f), 45f), 0.001f)
        assertEquals(1f, twistScale(Size.Zero, box, 30f), 0f) // nothing drawn yet
    }
}
