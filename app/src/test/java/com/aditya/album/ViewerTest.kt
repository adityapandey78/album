package com.aditya.album

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
