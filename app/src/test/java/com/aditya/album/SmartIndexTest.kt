package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartIndexTest {
    private fun media(id: Long, modified: Long = 100, size: Long = 10, isVideo: Boolean = false) = MediaStamp(id, isVideo, modified, size)
    private fun row(id: Long, modified: Long = 100, size: Long = 10, version: Int = 1, located: Boolean = true) =
        IndexStamp(id, modified, size, version, located)

    @Test fun analysesOnlyNewAndChangedItems() {
        val found = listOf(media(5), media(4), media(3, modified = 200), media(2, size = 11), media(1))
        val plan = planIndex(found, listOf(row(4), row(3), row(2), row(1)), version = 1, canLocate = true)
        assertEquals(listOf(5L, 3L, 2L), plan.analyse.map { it.id }) // new, edited, resized; order kept (newest first)
        assertEquals(emptyList<Long>(), plan.remove)
    }

    @Test fun redoesOlderVersions() {
        val plan = planIndex(listOf(media(1), media(2)), listOf(row(1, version = 1), row(2, version = 2)), version = 2, canLocate = true)
        assertEquals(listOf(1L), plan.analyse.map { it.id })
    }

    @Test fun relocatesOnceLocationAccessArrives() {
        val indexed = listOf(row(1, located = false), row(2))
        assertEquals(listOf(1L), planIndex(listOf(media(1), media(2)), indexed, 1, canLocate = true).analyse.map { it.id })
        assertEquals(emptyList<Long>(), planIndex(listOf(media(1), media(2)), indexed, 1, canLocate = false).analyse.map { it.id })
    }

    @Test fun removesRowsOfDeletedMedia() {
        val plan = planIndex(listOf(media(2)), listOf(row(1), row(2), row(3)), version = 1, canLocate = true)
        assertEquals(listOf(1L, 3L), plan.remove)
        assertEquals(emptyList<Long>(), plan.analyse.map { it.id })
    }

    @Test fun readsVideoLocationInIso6709() {
        assertArrayEquals(doubleArrayOf(12.9716, 77.5946), iso6709("+12.9716+077.5946/"), 1e-9)
        assertArrayEquals(doubleArrayOf(37.422, -122.0841), iso6709("+37.4220-122.0841+012.000/"), 1e-9) // with altitude
        assertArrayEquals(doubleArrayOf(-33.8688, 151.2093), iso6709("-33.8688+151.2093/"), 1e-9)
        assertNull(iso6709("+00.0000+000.0000/")) // a camera without a fix
        assertNull(iso6709("+95.0000+010.0000/"))
        assertNull(iso6709(""))
    }

    // Times in hours on day 0 (UTC), so a "day" is 24 hours of them
    private fun at(hours: Double) = (hours * 3_600_000).toLong()
    private val day = { millis: Long -> Math.floorDiv(millis, 86_400_000L) }
    private val goa = Place("Calangute", "Goa", "IN", "India")
    private val mumbai = Place("Mumbai", "Maharashtra", "IN", "India")
    private val pune = Place("Pune", "Maharashtra", "IN", "India")

    @Test fun photosWithoutGpsTakeTheNearestPlaceInTime() {
        val with = mapOf(1L to (at(10.0) to goa), 2L to (at(13.0) to mumbai))
        val without = mapOf(10L to at(11.0), 11L to at(12.5), 12L to at(9.0))
        assertEquals(mapOf(10L to goa, 11L to mumbai, 12L to goa), estimatePlaces(without, with, day))
    }

    @Test fun estimatesStayWithinTwoHoursAndTheSameDay() {
        val with = mapOf(1L to (at(13.0) to mumbai), 2L to (at(23.5) to pune), 3L to (at(26.5) to goa))
        val without = mapOf(
            10L to at(15.5), // 2.5 hours after Mumbai: too far
            11L to at(24.5), // an hour after Pune, but the next day: Goa, 2 hours later the same day
            12L to at(22.0), // Pune 1.5 hours later
        )
        assertEquals(mapOf(11L to goa, 12L to pune), estimatePlaces(without, with, day))
        assertEquals(emptyMap<Long, Place>(), estimatePlaces(without, emptyMap(), day))
    }

    @Test fun onlyThePhonesOwnCameraPhotosGiveOrGetEstimates() {
        val taken = 1_700_000_000_000L
        assertEquals(taken, captureTime(taken, "DCIM/Camera/", "IMG_20231114_101010.jpg", screenshot = false))
        assertEquals(taken, captureTime(taken, "DCIM/Camera/", "VID_20231114_101010.mp4", screenshot = false))
        // Arrived from somewhere else: when it arrived says nothing about where it was taken
        assertNull(captureTime(0, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "IMG-20231114-WA0012.jpg", screenshot = false))
        assertNull(captureTime(taken, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/", "VID-20231114-WA0003.mp4", screenshot = false))
        assertNull(captureTime(taken, "DCIM/", "IMG-20231114-WA0012.jpg", screenshot = false)) // moved into DCIM, still WhatsApp's
        assertNull(captureTime(taken, "Download/", "photo.jpg", screenshot = false))
        assertNull(captureTime(taken, "Pictures/Telegram/", "IMG_123.jpg", screenshot = false))
        assertNull(captureTime(0, "DCIM/Camera/", "IMG_20231114_101010.jpg", screenshot = false)) // no capture date
        assertNull(captureTime(taken, "DCIM/Screenshots/", "Screenshot_20231114-101010.png", screenshot = true))
    }
}
