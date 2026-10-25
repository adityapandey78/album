package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ForYouTest {
    private val today = LocalDate.of(2026, 10, 25)
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    /** Named photos on epoch days, sorted newest first the way the Library holds them. */
    private fun photos(vararg p: Pair<String, Long>) = p.sortedByDescending { it.second }
    private val dayOf = { p: Pair<String, Long> -> p.second }
    private fun names(m: Memory<Pair<String, Long>>?) = m?.items?.map { it.first }

    @Test fun spreadPicksEvenlyAcrossInOrder() {
        assertEquals(listOf(0, 5, 10, 15), spread((0 until 20).toList(), 4))
        assertEquals(listOf(1, 2), spread(listOf(1, 2), 20)) // fewer than asked: all of them
        assertEquals(emptyList<Int>(), spread(emptyList<Int>(), 20))
    }

    @Test fun onThisDayIsTheLatestEarlierYearOldestFirst() {
        val p = photos(
            "today" to day(2026, 10, 25), "b" to day(2025, 10, 25), "a" to day(2025, 10, 25),
            "older" to day(2023, 10, 25), "dayBefore" to day(2025, 10, 24),
        )
        val m = onThisDay(p, today, dayOf)!!
        assertEquals("On this day · 2025", m.eyebrow)
        assertEquals("1 year ago", m.title)
        assertEquals(listOf("a", "b"), names(m)) // the Library lists b first (newer), so oldest first is a, b
    }

    @Test fun onThisDayGoesBackFurtherWhenLastYearHasNone() {
        val m = onThisDay(photos("x" to day(2023, 10, 25), "y" to day(2025, 10, 26)), today, dayOf)!!
        assertEquals("On this day · 2023", m.eyebrow)
        assertEquals("3 years ago", m.title)
        assertNull(onThisDay(photos("y" to day(2025, 10, 26), "now" to day(2026, 10, 25)), today, dayOf))
    }

    @Test fun leapDayOnlyMatchesLeapYears() {
        val p = photos("leap" to day(2024, 2, 29), "feb28" to day(2027, 2, 28))
        assertEquals(listOf("leap"), names(onThisDay(p, LocalDate.of(2028, 2, 29), dayOf)))
        assertNull(onThisDay(p, LocalDate.of(2027, 3, 1), dayOf))
    }

    @Test fun weekLastYearIsThreeDaysEitherSideLeavingOutTheDayItself() {
        val p = photos(
            "tooEarly" to day(2025, 10, 21), "first" to day(2025, 10, 22), "onThisDay" to day(2025, 10, 25),
            "last" to day(2025, 10, 28), "tooLate" to day(2025, 10, 29), "thisYear" to day(2026, 10, 24),
        )
        val m = weekLastYear(p, today, dayOf)!!
        assertEquals("This week · 2025", m.eyebrow)
        assertEquals("22 to 28 Oct", m.title)
        assertEquals(listOf("first", "last"), names(m))
        assertNull(weekLastYear(photos("onThisDay" to day(2025, 10, 25)), today, dayOf))
    }

    @Test fun recapIsTheLatestEarlierMonthWithPhotos() {
        val p = photos(
            "thisMonth" to day(2026, 10, 3), "end" to day(2026, 8, 31), "start" to day(2026, 8, 1), "july" to day(2026, 7, 31),
        )
        val m = monthRecap(p, today, dayOf)!!
        assertEquals("August", m.title) // September had no photos
        assertEquals("Recap · 2026", m.eyebrow)
        assertEquals(listOf("start", "end"), names(m))
        assertNull(monthRecap(photos("thisMonth" to day(2026, 10, 3)), today, dayOf))
    }

    @Test fun recapCrossesTheYear() {
        val m = monthRecap(photos("nye" to day(2025, 12, 31)), LocalDate.of(2026, 1, 10), dayOf)!!
        assertEquals("December", m.title)
        assertEquals("Recap · 2025", m.eyebrow)
    }

    @Test fun dayRangesReadLikeTheRestOfAlbum() {
        assertEquals("24 Oct", dayRange(day(2025, 10, 24), day(2025, 10, 24)))
        assertEquals("20 to 26 Oct", dayRange(day(2025, 10, 20), day(2025, 10, 26)))
        assertEquals("29 Oct to 2 Nov", dayRange(day(2025, 10, 29), day(2025, 11, 2)))
    }

    @Test fun storageKinds() {
        assertEquals(StorageKind.Photos, storageKind(false, "DCIM/Camera/", "Camera", "IMG_20261025_101010.jpg"))
        assertEquals(StorageKind.Videos, storageKind(true, "DCIM/Camera/", "Camera", "VID_20261025_101010.mp4"))
        val whatsApp = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/"
        assertEquals(StorageKind.WhatsApp, storageKind(true, whatsApp, "WhatsApp Video", "VID-20261025-WA0001.mp4"))
        assertEquals(StorageKind.WhatsApp, storageKind(false, "Pictures/", "WhatsApp Images", "IMG-20261025-WA0001.jpg"))
        assertEquals(StorageKind.Screenshots, storageKind(false, "Pictures/Screenshots/", "Screenshots", "Screenshot_20261025.png"))
        assertEquals(StorageKind.Screenshots, storageKind(false, "DCIM/", "DCIM", "Screenshot_20261025.png"))
    }

    @Test fun storageAddsUpPerKindInBarOrderSkippingEmptyOnes() {
        val files = listOf(StorageKind.Screenshots to 5L, StorageKind.Photos to 100L, StorageKind.Photos to 20L, StorageKind.Screenshots to 1L)
        assertEquals(
            listOf(StorageKind.Photos to 120L, StorageKind.Screenshots to 6L),
            storageOf(files, { it.first }, { it.second }),
        )
        assertEquals(emptyList<Pair<StorageKind, Long>>(), storageOf(emptyList<Pair<StorageKind, Long>>(), { it.first }, { it.second }))
    }
}
