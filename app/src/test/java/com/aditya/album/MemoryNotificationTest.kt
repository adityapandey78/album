package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MemoryNotificationTest {
    private val today = LocalDate.of(2026, 10, 30)
    private fun day(y: Int, m: Int = 10, d: Int = 30) = LocalDate.of(y, m, d).toEpochDay()

    /** A photo as the check sees it: [junk] stands for a screenshot or a document. */
    private data class P(val id: Long, val day: Long, val path: String = "DCIM/Camera/", val junk: Boolean = false)

    private fun memory(photos: List<P>) = memoryOfTheDay(photos.sortedByDescending { it.day }, today, P::day) { !it.junk }

    private fun shots(year: Int, n: Int, from: Long, junk: Boolean = false, path: String = "DCIM/Camera/") =
        List(n) { P(from + it, day(year), path, junk) }

    @Test fun picksTheLatestEarlierYearWithEnoughPhotos() {
        val m = memory(shots(2023, 4, 10) + shots(2025, 2, 20) + shots(2026, 9, 30))!!
        assertEquals("On this day · 2023", m.eyebrow) // 2025 has two, too few to be an occasion; this year's never count
        assertEquals("3 years ago", m.title)
        assertEquals(listOf(10L, 11L, 12L, 13L), m.items.map { it.id }.sorted())
    }

    @Test fun screenshotsAndDocumentsAreNotAMemory() {
        assertNull(memory(shots(2025, 6, 10, junk = true)))
        // They don't count towards one either, nor play in it
        val m = memory(shots(2024, 2, 10) + shots(2024, 5, 20, junk = true) + shots(2022, 3, 30))!!
        assertEquals("On this day · 2022", m.eyebrow)
    }

    @Test fun nothingOnDaysWithNothing() {
        assertNull(memory(emptyList()))
        assertNull(memory(listOf(P(1, day(2025, 10, 29)), P(2, day(2025, 10, 29)), P(3, day(2025, 10, 29)))))
    }

    @Test fun hiddenItemsNeverMakeTheMemory() {
        val photos = shots(2025, 3, 10, path = "Pictures/Secret/") + shots(2024, 3, 20) + shots(2023, 3, 30)
        val h = HiddenState(folders = setOf(folderKey("Pictures/Secret/")), people = setOf(7), personPhotos = setOf(20))
        val visible = withoutHidden(photos, h, P::id, P::path)
        // 2025's folder is hidden and 2024 is down to two once a locked person's photo goes: 2023 it is
        assertEquals("On this day · 2023", memory(visible)!!.eyebrow)
        assertNull(memory(withoutHidden(shots(2025, 3, 10, path = "Pictures/Secret/"), h, P::id, P::path)))
    }

    @Test fun theLineSaysHowLongAgoAndWhere() {
        assertEquals("3 years ago today · Goa", memoryLine("3 years ago", "Goa"))
        assertEquals("1 year ago today", memoryLine("1 year ago", null))
    }

    @Test fun hoursFollowThePhonesClock() {
        assertEquals(listOf("7 am", "9 am", "12 pm", "6 pm", "9 pm"), MEMORY_HOURS.map { hourLabel(it, clock24 = false) })
        assertEquals(listOf("07:00", "09:00", "12:00", "18:00", "21:00"), MEMORY_HOURS.map { hourLabel(it, clock24 = true) })
        assertEquals("12 am", hourLabel(0, clock24 = false))
    }

    @Test fun theNextCheckIsTheNextTimeTheHourComesRound() {
        val morning = LocalDateTime.of(2026, 10, 30, 8, 59)
        assertEquals(LocalDateTime.of(2026, 10, 30, 9, 0), nextCheck(morning, 9))
        assertEquals(LocalDateTime.of(2026, 10, 31, 9, 0), nextCheck(morning.withHour(9).withMinute(0), 9))
        assertEquals(LocalDateTime.of(2026, 11, 1, 9, 0), nextCheck(LocalDateTime.of(2026, 10, 31, 22, 0), 9))
    }

    @Test fun aCheckNotifiesOnItsOwnDayFromTheHourAndOnce() {
        val due = day(2026)
        assertTrue(checksNow(LocalDateTime.of(2026, 10, 30, 9, 2), due, 9, lastDay = -1))
        assertTrue(checksNow(LocalDateTime.of(2026, 10, 30, 15, 0), due, 9, lastDay = -1)) // late, the phone was off: still today
        assertFalse(checksNow(LocalDateTime.of(2026, 10, 30, 8, 55), due, 9, lastDay = -1)) // early: the clock moved
        assertFalse(checksNow(LocalDateTime.of(2026, 10, 31, 9, 5), due, 9, lastDay = -1)) // a day late: skipped
        assertFalse(checksNow(LocalDateTime.of(2026, 10, 30, 12, 0), due, 12, lastDay = due)) // the hour moved after one came
    }
}
