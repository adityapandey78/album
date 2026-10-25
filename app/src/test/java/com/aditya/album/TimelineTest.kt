package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimelineTest {
    private val today = LocalDate.of(2026, 10, 25).toEpochDay()

    @Test fun groupsByDayWithFriendlyLabels() {
        val s = sections(listOf(today, today, today - 1, today - 400), byMonth = false, today = today) { it }
        assertEquals(listOf(2, 1, 1), s.map { it.items.size })
        assertEquals("Today", s[0].label)
        assertEquals("Yesterday", s[1].label)
        assertTrue(s[2].label.endsWith("2025")) // older years show the year
    }

    @Test fun groupsByMonth() {
        val s = sections(listOf(today, today - 20, today - 30), byMonth = true, today = today) { it }
        assertEquals(listOf(2, 1), s.map { it.items.size }) // Oct, Oct, Sep
    }

    @Test fun emptyListHasNoSections() {
        assertEquals(0, sections(emptyList<Long>(), byMonth = false, today = today) { it }.size)
    }
}
